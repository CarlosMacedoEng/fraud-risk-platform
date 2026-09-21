"""Graph-based risk: entity features over a rolling window, published as lookups.

Entities: account, device, IP, beneficiary, merchant. Edges come from transactions
(account–device, account–IP, account–beneficiary, card/account–merchant).

A snapshot at time ``as_of`` only uses transactions before ``as_of`` and fraud labels that were
*available* before ``as_of`` — the same information a near-real-time job would have in production
(ADR-005). Scores are transparent heuristics over graph features, so every graph reason can be
explained to an investigator ("this device was used by 6 different customers in 30 days, 2 of them
with confirmed fraud").
"""
from __future__ import annotations

from dataclasses import dataclass

import networkx as nx
import numpy as np
import pandas as pd

WINDOW_DAYS = 30
ROUND_PRICE_CENTS = {0, 90, 99}


def _clip01(x):
    return np.clip(x, 0.0, 1.0)


@dataclass
class GraphSnapshot:
    as_of: pd.Timestamp
    device: pd.DataFrame        # index device_id; columns: features..., risk
    beneficiary: pd.DataFrame
    merchant: pd.DataFrame
    account: pd.DataFrame

    def lookup(self, kind: str, keys: pd.Series) -> np.ndarray:
        table: pd.DataFrame = getattr(self, kind)
        if table.empty:
            return np.zeros(len(keys), dtype=np.float32)
        return keys.map(table["risk"]).fillna(0.0).to_numpy(dtype=np.float32)


def build_snapshot(tx: pd.DataFrame, as_of: pd.Timestamp, merchant_first_seen: pd.Series) -> GraphSnapshot:
    window = tx[(tx["event_time"] < as_of) & (tx["event_time"] >= as_of - pd.Timedelta(days=WINDOW_DAYS))]
    known_fraud = window["is_fraud"] & window["label_available_at"].notna() & (window["label_available_at"] < as_of)
    window = window.assign(known_fraud=known_fraud)
    fraud_customers = set(window.loc[known_fraud, "customer_id"])

    # --- devices: how many customers share it, and how many of them have confirmed fraud
    dev = window.dropna(subset=["device_id"]).groupby("device_id").agg(
        distinct_customers=("customer_id", "nunique"),
        fraud_txns=("known_fraud", "sum"),
    )
    if not dev.empty:
        dev_fraud_customers = (window.dropna(subset=["device_id"])
                               .assign(fc=lambda d: d["customer_id"].isin(fraud_customers))
                               .groupby("device_id")["fc"].any())
        dev["fraud_linked"] = dev_fraud_customers.reindex(dev.index).fillna(False).astype(bool)
        dev["risk"] = (0.6 * _clip01((dev["distinct_customers"] - 1) / 4.0)
                       + 0.4 * ((dev["fraud_txns"] > 0) | (dev["fraud_linked"] & (dev["distinct_customers"] > 1))))

    # --- beneficiaries: fan-in from unrelated senders, confirmed fraud inbound
    transfers = window[window["transaction_type"] == "TRANSFER"]
    ben = transfers.groupby("beneficiary_id").agg(
        distinct_senders=("customer_id", "nunique"),
        fraud_inbound=("known_fraud", "sum"),
    )
    if not ben.empty:
        ben["risk"] = 0.5 * _clip01((ben["distinct_senders"] - 2) / 5.0) + 0.5 * (ben["fraud_inbound"] > 0)

    # --- merchants: new, round price points, night-time sales, many cards (laundering indicators)
    cards = window[window["transaction_type"] == "CARD_PAYMENT"]
    cents = (cards["amount"] * 100).round().astype("int64") % 100
    hours = cards["event_time"].dt.hour
    mer = cards.assign(round_price=cents.isin(ROUND_PRICE_CENTS), night=hours < 5).groupby("merchant_id").agg(
        distinct_cards=("card_token", "nunique"),
        round_share=("round_price", "mean"),
        night_share=("night", "mean"),
        fraud_txns=("known_fraud", "sum"),
    )
    if not mer.empty:
        age_days = (as_of - merchant_first_seen.reindex(mer.index)).dt.days
        mer["age_days"] = age_days
        eligible = mer["distinct_cards"] >= 5
        mer["risk"] = eligible * (0.3 * (mer["age_days"] < 45) + 0.3 * (mer["round_share"] > 0.6)
                                  + 0.2 * (mer["night_share"] > 0.35) + 0.2 * (mer["fraud_txns"] > 0))

    # --- accounts: connected components over shared devices / IPs
    acc = _account_components(window, fraud_customers)

    return GraphSnapshot(as_of, dev, ben, mer, acc)


def _account_components(window: pd.DataFrame, fraud_customers: set[str]) -> pd.DataFrame:
    g = nx.Graph()
    for col, prefix in (("device_id", "d:"), ("ip_address", "i:")):
        edges = window.dropna(subset=[col])[["customer_id", col]].drop_duplicates()
        # Only shared entities create links between accounts; skip entities used by a single customer.
        shared = edges.groupby(col)["customer_id"].transform("nunique") > 1
        for cust, ent in edges[shared].itertuples(index=False):
            g.add_edge("c:" + cust, prefix + ent)
    rows = []
    for comp in nx.connected_components(g):
        members = [n[2:] for n in comp if n.startswith("c:")]
        n_fraud = sum(1 for m in members if m in fraud_customers)
        for m in members:
            rows.append((m, len(members), n_fraud))
    acc = pd.DataFrame(rows, columns=["customer_id", "component_accounts", "component_fraud_accounts"])
    if acc.empty:
        return acc.set_index("customer_id").assign(risk=[])
    acc = acc.set_index("customer_id")
    acc["risk"] = (0.5 * _clip01((acc["component_accounts"] - 2) / 4.0)
                   + 0.5 * (acc["component_fraud_accounts"] > 0))
    return acc


def graph_features(tx: pd.DataFrame, *, refresh: str = "1D", start: pd.Timestamp | None = None) -> pd.DataFrame:
    """Graph risk per transaction using the snapshot refreshed at the start of each period.

    With ``refresh='1D'`` a transaction on day *d* sees the graph as of midnight of day *d*: it
    simulates a job that runs daily. Production target is minutes (documented limitation).
    """
    merchant_first_seen = tx.groupby("merchant_id")["event_time"].min()
    out = pd.DataFrame(0.0, index=tx.index, columns=["graph_device_risk", "graph_beneficiary_risk",
                                                     "graph_merchant_risk", "graph_account_risk"],
                       dtype=np.float32)
    periods = tx["event_time"].dt.floor(refresh)
    for as_of, idx in tx.groupby(periods).groups.items():
        if start is not None and as_of < start:
            continue
        snap = build_snapshot(tx, as_of, merchant_first_seen)
        part = tx.loc[idx]
        out.loc[idx, "graph_device_risk"] = snap.lookup("device", part["device_id"])
        out.loc[idx, "graph_beneficiary_risk"] = snap.lookup("beneficiary", part["beneficiary_id"])
        out.loc[idx, "graph_merchant_risk"] = snap.lookup("merchant", part["merchant_id"])
        out.loc[idx, "graph_account_risk"] = snap.lookup("account", part["customer_id"])
    return out


def export_snapshot(snap: GraphSnapshot) -> list[dict]:
    """Flatten a snapshot into records for the Redis publisher (key = graph:<kind>:<id>)."""
    records = []
    for kind in ("device", "beneficiary", "merchant", "account"):
        table: pd.DataFrame = getattr(snap, kind)
        if table.empty:
            continue
        risky = table[table["risk"] > 0]
        for key, row in risky.iterrows():
            feats = {k: (float(v) if isinstance(v, (int, float, np.number, bool, np.bool_)) else str(v))
                     for k, v in row.items() if k != "risk"}
            records.append({"kind": kind, "id": key, "risk": round(float(row["risk"]), 4),
                            "features": feats, "as_of": snap.as_of.isoformat()})
    return records

"""Artifacts consumed by the Java platform: feature-parity golden stream and graph snapshots."""
from __future__ import annotations

import json

import pandas as pd

from . import paths
from .dataset import load_raw
from .features import BASE_FEATURES, FEATURE_SPEC_VERSION, compute_features
from .graph import build_snapshot, export_snapshot


def export_feature_parity(customer: str, n_customers: int = 60, days: int = 3) -> int:
    """A self-contained event stream + profiles + expected features.

    The Java ``FeatureCalculatorParityTest`` replays this stream from an empty state and must produce
    the same vectors (tolerance 1e-5). Fraud victims are included so velocity/new-device paths are hit.
    """
    tx, customers, _ = load_raw(customer)
    start = tx["event_time"].max().floor("D") - pd.Timedelta(days=days + 5)
    window = tx[(tx["event_time"] >= start) & (tx["event_time"] < start + pd.Timedelta(days=days))]
    fraud_customers = window.loc[window["is_fraud"], "customer_id"].unique().tolist()
    others = window.loc[~window["customer_id"].isin(fraud_customers), "customer_id"].drop_duplicates()
    chosen = fraud_customers[: n_customers // 2] + others.sample(n_customers - min(len(fraud_customers), n_customers // 2),
                                                                random_state=11).tolist()
    stream = window[window["customer_id"].isin(chosen)]
    cust = customers[customers["customer_id"].isin(chosen)]
    feats = compute_features(stream, cust)
    profiles = cust.set_index("customer_id")
    out = paths.MODELS_DIR / customer / "parity"
    out.mkdir(parents=True, exist_ok=True)
    with (out / f"feature_parity_{FEATURE_SPEC_VERSION}.jsonl").open("w", encoding="utf-8") as f:
        for (i, r), (_, fr) in zip(stream.iterrows(), feats.iterrows()):
            p = profiles.loc[r["customer_id"]]
            clean = lambda v: None if pd.isna(v) else v  # noqa: E731
            f.write(json.dumps({
                "event": {"transactionId": r["transaction_id"], "eventTime": r["event_time"].isoformat(),
                          "customerId": r["customer_id"], "accountId": r["account_id"],
                          "cardToken": clean(r["card_token"]), "transactionType": r["transaction_type"],
                          "channel": r["channel"], "amount": float(r["amount"]), "currency": r["currency"],
                          "merchantId": clean(r["merchant_id"]), "mcc": clean(r["mcc"]),
                          "merchantCountry": clean(r["merchant_country"]), "beneficiaryId": clean(r["beneficiary_id"]),
                          "beneficiaryCountry": clean(r["beneficiary_country"]), "deviceId": clean(r["device_id"]),
                          "ipAddress": clean(r["ip_address"]), "ipCountry": clean(r["ip_country"])},
                "profile": {"customerId": r["customer_id"], "segment": p["segment"], "homeCountry": p["home_country"],
                            "tenureDays": int(p["tenure_days"]), "avgAmount90d": float(p["avg_amount_90d"]),
                            "riskTier": p["risk_tier"],
                            "boundDeviceIds": [d for d in (p["bound_device_ids"] or "").split(";") if d]},
                "expected": {k: float(fr[k]) for k in BASE_FEATURES},
            }) + "\n")
    return len(stream)


def export_graph_snapshot(customer: str) -> int:
    """Latest graph snapshot (as of the end of the data) for the Redis loader."""
    tx, _, _ = load_raw(customer)
    as_of = tx["event_time"].max().ceil("D")
    snap = build_snapshot(tx, as_of, tx.groupby("merchant_id")["event_time"].min())
    records = export_snapshot(snap)
    out = paths.MODELS_DIR / customer / "graph"
    out.mkdir(parents=True, exist_ok=True)
    with (out / "graph-features-latest.jsonl").open("w", encoding="utf-8") as f:
        for rec in records:
            f.write(json.dumps(rec) + "\n")
    return len(records)


def export_threat_feed(customers: list[str], coverage: float = 0.4, seed: int = 7) -> int:
    """Simulated vendor threat feed: a *partial* sample of IPs used by fraudsters.

    Real threat-intelligence feeds never cover every attacker; 40% coverage keeps COMPROMISED_IP a useful
    but incomplete signal, which is the realistic situation.
    """
    ips: set[str] = set()
    for customer in customers:
        tx, _, _ = load_raw(customer)
        fraud = tx[tx["is_fraud"] & tx["fraud_type"].isin(["stolen_card", "card_testing", "account_takeover"])]
        ips.update(fraud["ip_address"].dropna().unique().tolist())
    ordered = sorted(ips)
    rng = pd.Series(ordered).sample(frac=coverage, random_state=seed).sort_values()
    out = paths.SAMPLES_DIR / "threat_feed_ips.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Simulated threat-intelligence feed (synthetic, partial coverage)", *rng]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(rng)

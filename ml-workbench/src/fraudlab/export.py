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


# ---------------------------------------------------------------------------------------------------------------
# Legacy file samples for the file-adapter (see docs/FILE_INTEGRATIONS.md)

HISTORY_COLUMNS = ["transaction_id", "event_time", "customer_id", "account_id", "transaction_type", "channel", "amount",
                   "currency", "card_token", "merchant_id", "mcc", "merchant_country", "beneficiary_id",
                   "beneficiary_country", "device_id", "ip_address", "ip_country"]


def _write_with_marker(path, text: str, records: int) -> None:
    import hashlib
    path.parent.mkdir(parents=True, exist_ok=True)
    data = text.encode("utf-8")
    path.write_bytes(data)
    (path.parent / (path.name + ".done")).write_text(
        f"records={records}\nsha256={hashlib.sha256(data).hexdigest()}\n", encoding="utf-8")


def _csv(frame: pd.DataFrame, columns: list[str]) -> str:
    out = frame[columns].copy()
    for c in out.columns:
        if pd.api.types.is_datetime64_any_dtype(out[c]):
            out[c] = out[c].dt.strftime("%Y-%m-%dT%H:%M:%S.%f").str[:-3] + "Z"
    return out.to_csv(index=False, lineterminator="\n", float_format="%.2f")


def export_legacy_files(customer: str, history_rows: int = 600) -> dict:
    """Sample inbound files for one tenant, plus deliberately broken variants for the troubleshooting lab."""
    import json as _json
    tx, customers, _ = load_raw(customer)
    end = tx["event_time"].max().normalize()
    day = end.strftime("%Y%m%d")
    out = paths.SAMPLES_DIR / "files" / customer
    lab = paths.SAMPLES_DIR / "files" / "troubleshooting" / customer
    counts = {}

    hist = tx[tx["event_time"] >= end - pd.Timedelta(days=1)].head(history_rows).copy()
    hist["amount"] = hist["amount"].map(lambda v: f"{v:.2f}")
    text = _csv(hist.rename(columns={}), HISTORY_COLUMNS)
    _write_with_marker(out / f"TXN_HISTORY_{customer}_{day}_001.csv", text, len(hist))
    counts["TXN_HISTORY"] = len(hist)

    labelled = tx[tx["is_fraud"] & tx["label_available_at"].notna()]
    cb = labelled[labelled["fraud_type"].isin(["stolen_card", "card_testing", "geo_counterfeit"])].head(40).copy()
    cb["chargeback_date"] = cb["label_available_at"].dt.strftime("%Y-%m-%d")
    cb["reason_code"] = "4837"
    cb["amount"] = cb["amount"].map(lambda v: f"{v:.2f}")
    _write_with_marker(out / f"CHARGEBACKS_{customer}_{day}_001.csv",
                       _csv(cb, ["transaction_id", "chargeback_date", "reason_code", "amount", "currency", "customer_id"]), len(cb))
    counts["CHARGEBACKS"] = len(cb)

    other = labelled[~labelled["fraud_type"].isin(["stolen_card", "card_testing", "geo_counterfeit"])].head(30)
    genuine = tx[~tx["is_fraud"]].sample(10, random_state=3)
    lines = [_json.dumps({"transactionId": r.transaction_id, "customerId": r.customer_id, "label": "FRAUD",
                          "fraudType": r.fraud_type, "reportedAt": r.label_available_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
                          "source": "CUSTOMER_REPORT"}) for r in other.itertuples()]
    lines += [_json.dumps({"transactionId": r.transaction_id, "customerId": r.customer_id, "label": "GENUINE",
                           "fraudType": None, "reportedAt": end.strftime("%Y-%m-%dT%H:%M:%SZ"), "source": "BATCH_LABEL"})
              for r in genuine.itertuples()]
    _write_with_marker(out / f"FRAUD_LABELS_{customer}_{day}_001.jsonl", "\n".join(lines) + "\n", len(lines))
    counts["FRAUD_LABELS"] = len(lines)

    prof = [_json.dumps({"customerId": r.customer_id, "segment": r.segment, "homeCountry": r.home_country,
                         "tenureDays": int(r.tenure_days), "avgAmount90d": f"{r.avg_amount_90d:.2f}", "riskTier": r.risk_tier,
                         "boundDeviceIds": [d for d in (r.bound_device_ids or "").split(";") if d]})
            for r in customers.itertuples()]
    _write_with_marker(out / f"CUSTOMER_PROFILES_{customer}_{day}_001.jsonl", "\n".join(prof) + "\n", len(prof))
    counts["CUSTOMER_PROFILES"] = len(prof)

    # --- troubleshooting variants (incidents TS-09 malformed file, TS-10 schema mismatch)
    small = hist.head(50)
    swapped = _csv(small, HISTORY_COLUMNS).replace("amount,currency", "currency,amount", 1)
    _write_with_marker(lab / f"TXN_HISTORY_{customer}_{day}_901.csv", swapped, len(small))
    bad = small.copy()
    bad.loc[bad.index[:8], "amount"] = "12,50"          # decimal comma from a spreadsheet export
    bad.loc[bad.index[8:10], "event_time"] = pd.NaT       # missing timestamps
    _write_with_marker(lab / f"TXN_HISTORY_{customer}_{day}_902.csv", _csv(bad, HISTORY_COLUMNS), len(bad))
    good = _csv(small, HISTORY_COLUMNS)
    truncated = "\n".join(good.splitlines()[:30]) + "\n"
    _write_with_marker(lab / f"TXN_HISTORY_{customer}_{day}_903.csv", truncated, len(small))  # marker says 50
    (lab / f"transactions_{customer}_{day}.csv").write_text(good, encoding="utf-8")            # wrong name
    return counts


def export_perf_pool(customer: str, n: int = 5000, seed: int = 21) -> int:
    """Transaction templates for k6 (IDs and timestamps are generated per request by the load script)."""
    import json as _json
    tx, _, _ = load_raw(customer)
    start = tx["event_time"].min() + (tx["event_time"].max() - tx["event_time"].min()) * 0.8
    pool = tx[tx["event_time"] >= start].sample(n, random_state=seed)
    cols = {"customer_id": "customerId", "account_id": "accountId", "transaction_type": "transactionType", "channel": "channel",
            "amount": "amount", "currency": "currency", "card_token": "cardToken", "merchant_id": "merchantId", "mcc": "mcc",
            "merchant_country": "merchantCountry", "beneficiary_id": "beneficiaryId", "beneficiary_country": "beneficiaryCountry",
            "device_id": "deviceId", "ip_address": "ipAddress", "ip_country": "ipCountry"}
    records = []
    for r in pool[list(cols)].itertuples(index=False):
        rec = {}
        for (src, dst), v in zip(cols.items(), r):
            if v is not None and not (isinstance(v, float) and pd.isna(v)):
                rec[dst] = round(float(v), 2) if src == "amount" else v
        records.append(rec)
    out = paths.REPO_ROOT / "perf" / "data" / f"pool-{customer}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(_json.dumps(records), encoding="utf-8")
    return len(records)

"""Load generated data, compute/cache features and build leakage-safe time-based splits."""
from __future__ import annotations

import time
from dataclasses import dataclass

import numpy as np
import pandas as pd

from . import paths
from .features import BASE_FEATURES, FEATURE_SPEC_VERSION, GRAPH_FEATURES, compute_features
from .graph import graph_features

RAW_CONTEXT = ["transaction_id", "event_time", "customer_id", "amount", "channel", "transaction_type", "mcc",
               "merchant_country", "ip_country", "beneficiary_country", "device_id", "merchant_id",
               "beneficiary_id", "card_token"]

# Fractions of the period. Warm-up rows are excluded because velocity/"seen" features need history.
# Between VALID_END and TEST_START is a label-maturation gap: the model is trained at TEST_START, so
# transactions just before it have mostly unknown labels (chargebacks take weeks) and are not used.
WARMUP_END, TRAIN_END, VALID_END, TEST_START = 0.10, 0.52, 0.67, 0.80


@dataclass
class Splits:
    frame: pd.DataFrame           # all rows after warm-up: raw context + features + labels + split
    label_cutoff: pd.Timestamp    # the moment the model is (notionally) trained
    boundaries: dict

    def part(self, name: str) -> pd.DataFrame:
        return self.frame[self.frame["split"] == name]


def load_raw(customer: str) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    d = paths.dataset_dir(customer)
    return (pd.read_parquet(d / "transactions.parquet"), pd.read_parquet(d / "customers.parquet"),
            pd.read_parquet(d / "merchants.parquet"))


def load_features(customer: str, tx: pd.DataFrame, customers: pd.DataFrame, *, refresh: bool = False) -> pd.DataFrame:
    cache = paths.dataset_dir(customer) / f"features-{FEATURE_SPEC_VERSION}.parquet"
    if cache.exists() and not refresh:
        feats = pd.read_parquet(cache)
        if len(feats) == len(tx):
            return feats
    t0 = time.perf_counter()
    base = compute_features(tx, customers)
    print(f"[features] base features for {len(tx):,} rows in {time.perf_counter() - t0:.1f}s")
    t0 = time.perf_counter()
    graph = graph_features(tx)
    print(f"[features] graph features (daily snapshots) in {time.perf_counter() - t0:.1f}s")
    feats = pd.concat([base, graph], axis=1)
    feats.to_parquet(cache, index=False)
    return feats


def build_splits(customer: str, *, refresh: bool = False) -> Splits:
    tx, customers, _ = load_raw(customer)
    feats = load_features(customer, tx, customers, refresh=refresh)
    start, end = tx["event_time"].min().floor("D"), tx["event_time"].max().ceil("D")
    span = end - start
    b = {name: start + span * frac for name, frac in
         (("warmup_end", WARMUP_END), ("train_end", TRAIN_END), ("valid_end", VALID_END),
          ("test_start", TEST_START))}
    b["start"], b["end"] = start, end
    for k in ("warmup_end", "train_end", "valid_end", "test_start"):
        b[k] = b[k].floor("D")

    frame = pd.concat([tx[RAW_CONTEXT + ["is_fraud", "fraud_type", "scenario_id", "label_available_at"]],
                       feats[BASE_FEATURES + GRAPH_FEATURES]], axis=1)
    frame = frame.merge(customers[["customer_id", "segment"]].rename(columns={"segment": "customer_segment"}),
                        on="customer_id", how="left")
    t = frame["event_time"]
    frame["split"] = np.select(
        [t < b["warmup_end"], t < b["train_end"], t < b["valid_end"], t < b["test_start"]],
        ["warmup", "train", "valid", "gap"], default="test")
    frame = frame[~frame["split"].isin(["warmup", "gap"])].reset_index(drop=True)

    # Observed label = what the institution knew at training time (label delay + unreported fraud).
    label_cutoff = b["test_start"]
    frame["label_observed"] = (frame["is_fraud"] & frame["label_available_at"].notna()
                               & (frame["label_available_at"] < label_cutoff))
    return Splits(frame, label_cutoff, b)


def label_coverage(splits: Splits) -> dict:
    out = {}
    for name in ("train", "valid", "test"):
        p = splits.part(name)
        fraud = int(p["is_fraud"].sum())
        out[name] = {
            "rows": int(len(p)),
            "fraud_ground_truth": fraud,
            "fraud_labelled_at_cutoff": int(p["label_observed"].sum()),
            "coverage": round(float(p["label_observed"].sum() / fraud), 3) if fraud else None,
            "from": str(p["event_time"].min()), "to": str(p["event_time"].max()),
        }
    return out

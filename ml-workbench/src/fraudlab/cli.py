"""Command-line entry point: python -m fraudlab.cli <command> [options]."""
from __future__ import annotations

import argparse
import json
import time

import pandas as pd

from . import paths
from .config import PROFILES


def cmd_generate(args: argparse.Namespace) -> None:
    from .generate import generate

    profile = PROFILES[args.customer]
    if args.scale != 1.0:
        profile = profile.scaled(args.scale)
    started = time.perf_counter()
    ds = generate(profile, seed=args.seed)
    out = paths.dataset_dir(args.customer)
    out.mkdir(parents=True, exist_ok=True)
    ds.transactions.to_parquet(out / "transactions.parquet", index=False)
    ds.customers.to_parquet(out / "customers.parquet", index=False)
    ds.merchants.to_parquet(out / "merchants.parquet", index=False)
    ds.summary["generation_seconds"] = round(time.perf_counter() - started, 1)
    (out / "summary.json").write_text(json.dumps(ds.summary, indent=2))

    # Small committed samples for documentation and for Java/file-adapter tests.
    samples = paths.SAMPLES_DIR / args.customer
    samples.mkdir(parents=True, exist_ok=True)
    fraud = ds.transactions[ds.transactions["is_fraud"]].groupby("fraud_type").head(15)
    legit = ds.transactions[~ds.transactions["is_fraud"]].sample(400, random_state=7)
    sample = pd.concat([fraud, legit]).sort_values("event_time")
    sample.to_csv(samples / "transactions_sample.csv", index=False)
    ds.customers.head(200).to_csv(samples / "customers_sample.csv", index=False)
    print(json.dumps(ds.summary, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(prog="fraudlab")
    sub = parser.add_subparsers(dest="command", required=True)

    g = sub.add_parser("generate", help="generate a synthetic dataset for a customer profile")
    g.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    g.add_argument("--scale", type=float, default=1.0, help="shrink the profile (e.g. 0.1) for quick runs")
    g.add_argument("--seed", type=int, default=None)
    g.set_defaults(func=cmd_generate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

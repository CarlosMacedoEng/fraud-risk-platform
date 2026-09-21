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


def cmd_train(args: argparse.Namespace) -> None:
    from .train import export_and_register

    m = export_and_register(args.customer, args.version, args.feature_set)
    print(json.dumps({"modelVersion": m["modelVersion"], "metrics": m["metrics"],
                      "exportParity": m["exportParity"], "labelCoverage": m["training"]["labelCoverage"]},
                     indent=2))


def cmd_evaluate(args: argparse.Namespace) -> None:
    from .evaluate import evaluate

    r = evaluate(args.customer, args.model_version, args.strategy_version)
    for name, v in r["variants"].items():
        m = v["test"]
        print(f"{name:24s} P={m['precision']:.3f} R={m['recall']:.3f} F1={m['f1']:.3f} PR-AUC={m['pr_auc']:.3f} "
              f"rev/day={m['reviews_per_day']} dec/day={m['declines_per_day']} cost={m['estimated_total_cost']:.0f}")


def cmd_explain(args: argparse.Namespace) -> None:
    from .explain import explain

    r = explain(args.customer, args.model_version, args.strategy_version)
    print(json.dumps({"pairs": len(r["contrastingPairs"]), "top_features": list(r["globalImportance"])[:5]}))


def cmd_export(args: argparse.Namespace) -> None:
    from .export import export_feature_parity, export_graph_snapshot, export_threat_feed

    print(json.dumps({"parity_events": export_feature_parity(args.customer),
                      "graph_records": export_graph_snapshot(args.customer),
                      "threat_feed_ips": export_threat_feed(sorted(PROFILES))}))


def main() -> None:
    parser = argparse.ArgumentParser(prog="fraudlab")
    sub = parser.add_subparsers(dest="command", required=True)

    g = sub.add_parser("generate", help="generate a synthetic dataset for a customer profile")
    g.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    g.add_argument("--scale", type=float, default=1.0, help="shrink the profile (e.g. 0.1) for quick runs")
    g.add_argument("--seed", type=int, default=None)
    g.set_defaults(func=cmd_generate)

    t = sub.add_parser("train", help="train, export to ONNX and register a model version")
    t.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    t.add_argument("--version", required=True, help="e.g. aldermoor-bank-lgbm-1.0.0")
    t.add_argument("--feature-set", choices=["base", "graph"], default="base")
    t.set_defaults(func=cmd_train)

    e = sub.add_parser("evaluate", help="compare rules-only, ML-only and hybrid strategies on the test window")
    e.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    e.add_argument("--model-version", required=True)
    e.add_argument("--strategy-version", default="1.0.0")
    e.set_defaults(func=cmd_evaluate)

    x = sub.add_parser("explain", help="SHAP importance, contrasting examples, FP/FN analysis")
    x.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    x.add_argument("--model-version", required=True)
    x.add_argument("--strategy-version", default="1.1.0")
    x.set_defaults(func=cmd_explain)

    ex = sub.add_parser("export", help="export Java parity stream and graph snapshot")
    ex.add_argument("--customer", choices=sorted(PROFILES), default="aldermoor-bank")
    ex.set_defaults(func=cmd_export)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

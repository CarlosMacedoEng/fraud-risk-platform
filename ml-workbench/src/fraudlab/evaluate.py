"""Offline evaluation of decision strategies on the held-out (most recent) time window.

Variants compared (all use the *exported ONNX artifacts*, not in-memory models):

1. rules_only          deterministic strategy rules; score = points / 100
2. lr_only             logistic-regression baseline (transparent ML)
3. ml_only             LightGBM probability
4. hybrid_rules_ml     noisy-OR(model, rules)
5. hybrid_full         noisy-OR(model, rules, graph, anomaly)
6. strategy_as_configured  the strategy JSON exactly as it would be deployed (weights, thresholds, overrides)

Thresholds for variants 1–5 are chosen on the validation window with labels known at training time:
* review threshold -> flag rate equals the review budget (share of daily transactions),
* decline threshold -> lowest score whose validation precision reaches ``DECLINE_PRECISION``.
Test metrics use ground truth, i.e. "evaluated in hindsight once labels have matured".
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass

import numpy as np
import onnxruntime as ort
import pandas as pd
from sklearn.metrics import average_precision_score, roc_auc_score

from . import paths
from .dataset import Splits, build_splits
from .features import BASE_FEATURES
from .strategy import combine, decide, evaluate_rules, load_strategy, thresholds_for
from .train import anomaly_percentile, model_dir

REVIEW_BUDGET = {"aldermoor-bank": 0.003, "quillon-pay": 0.002}   # share of daily transactions
DECLINE_PRECISION = 0.80
COST = {"false_decline": 10.0, "review": 3.0}                       # EUR, assumptions
WEIGHT_GRID = {"rules": [0.3, 0.5, 0.7], "graph": [0.4, 0.6, 0.8], "anomaly": [0.0, 0.2, 0.4]}


@dataclass
class Scored:
    frame: pd.DataFrame
    model: np.ndarray
    lr: np.ndarray
    anomaly_pct: np.ndarray
    graph: np.ndarray
    rule_points: np.ndarray
    min_rank: np.ndarray
    rule_hits: pd.DataFrame


class ModelArtifacts:
    def __init__(self, customer: str, version: str):
        self.dir = model_dir(customer, version)
        self.manifest = json.loads((self.dir / "manifest.json").read_text(encoding="utf-8"))
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        self.sup = ort.InferenceSession(str(self.dir / "supervised.onnx"), opts, providers=["CPUExecutionProvider"])
        self.iso = ort.InferenceSession(str(self.dir / "anomaly.onnx"), opts, providers=["CPUExecutionProvider"])
        self.lr = ort.InferenceSession(str(self.dir / "baseline_lr.onnx"), opts, providers=["CPUExecutionProvider"])
        self.features = self.manifest["features"]
        self.raw_quantiles = np.asarray(self.manifest["anomaly"]["rawQuantiles"])

    def supervised(self, x: np.ndarray) -> np.ndarray:
        return self.sup.run(None, {"features": x})[1][:, 1]

    def baseline(self, x: np.ndarray) -> np.ndarray:
        return self.lr.run(None, {self.lr.get_inputs()[0].name: x})[1][:, 1]

    def anomaly_pct(self, x_base: np.ndarray) -> np.ndarray:
        raw = -self.iso.run(None, {self.iso.get_inputs()[0].name: x_base})[1].ravel()
        return anomaly_percentile(raw, self.raw_quantiles)


def score_frame(frame: pd.DataFrame, art: ModelArtifacts, strategy: dict) -> Scored:
    x = frame[art.features].to_numpy(np.float32)
    xb = frame[BASE_FEATURES].to_numpy(np.float32)
    graph = frame[["graph_device_risk", "graph_beneficiary_risk", "graph_merchant_risk",
                   "graph_account_risk"]].max(axis=1).to_numpy()
    points, min_rank, hits = evaluate_rules(frame, strategy)
    return Scored(frame, art.supervised(x), art.baseline(x), art.anomaly_pct(xb), graph,
                  points.to_numpy(), min_rank.to_numpy(), hits)


def variant_scores(s: Scored, name: str, weights: dict | None, tail: float) -> np.ndarray:
    if name == "rules_only":
        return np.clip(s.rule_points / 100.0, 0, 1)
    if name == "lr_only":
        return s.lr
    if name == "ml_only":
        return s.model
    return combine(s.model, s.rule_points, s.graph, s.anomaly_pct, weights, tail)


def choose_thresholds(score: np.ndarray, y_obs: np.ndarray, budget: float) -> tuple[float, float]:
    review = float(np.quantile(score, 1 - budget))
    order = np.argsort(-score)
    ys, ss = y_obs[order], score[order]
    precision = np.cumsum(ys) / np.arange(1, len(ys) + 1)
    ok = np.where((precision >= DECLINE_PRECISION) & (np.cumsum(ys) >= 5))[0]
    decline = float(ss[ok.max()]) if len(ok) else 1.01
    decline = max(decline, review)
    return round(review, 6), round(decline, 6)


def operating_metrics(frame: pd.DataFrame, score: np.ndarray, rank: np.ndarray, budget: float) -> dict:
    y = frame["is_fraud"].to_numpy()
    amount = frame["amount"].to_numpy()
    days = max(1, frame["event_time"].dt.normalize().nunique())
    flagged, declined, reviewed = rank >= 1, rank == 2, rank == 1
    tp, fp = int((flagged & y).sum()), int((flagged & ~y).sum())
    fn, tn = int((~flagged & y).sum()), int((~flagged & ~y).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    missed_amount = float(amount[y & ~flagged].sum())
    false_declines = int((declined & ~y).sum())
    total_cost = missed_amount + COST["false_decline"] * false_declines + COST["review"] * int(reviewed.sum())

    # precision within the daily review capacity (top-K by score each day)
    day = frame["event_time"].dt.normalize().to_numpy()
    sel = np.zeros(len(frame), dtype=bool)
    for d in np.unique(day):
        idx = np.where(day == d)[0]
        k = max(1, int(np.ceil(budget * len(idx))))
        sel[idx[np.argsort(-score[idx])[:k]]] = True

    by_type = {}
    for ftype, g in frame[frame["is_fraud"]].groupby("fraud_type"):
        by_type[ftype] = round(float(flagged[g.index].mean()), 3)
    emerging = frame["scenario_id"].fillna("").str.startswith("EMG").to_numpy()
    if emerging.any():
        by_type["account_takeover (emerging variant)"] = round(float(flagged[emerging].mean()), 3)
    scen = frame.loc[frame["is_fraud"], "scenario_id"]
    incident_recall = float(pd.Series(flagged[scen.index], index=scen.index).groupby(scen).any().mean())

    return {
        "roc_auc": round(float(roc_auc_score(y, score)), 4),
        "pr_auc": round(float(average_precision_score(y, score)), 4),
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0,
        "false_positive_rate": round(fp / (fp + tn), 5) if fp + tn else 0.0,
        "false_negative_rate": round(fn / (fn + tp), 4) if fn + tp else 0.0,
        "decline_precision": round(float((declined & y).sum() / declined.sum()), 4) if declined.any() else None,
        "precision_at_review_capacity": round(float(y[sel].mean()), 4),
        "recall_at_review_capacity": round(float(y[sel].sum() / y.sum()), 4),
        "reviews_per_day": round(float(reviewed.sum() / days), 1),
        "declines_per_day": round(float(declined.sum() / days), 1),
        "legit_flagged_per_1000_legit": round(1000 * fp / (fp + tn), 3) if fp + tn else 0.0,
        "false_declines": false_declines,
        "fraud_amount_total": round(float(amount[y].sum()), 2),
        "fraud_amount_missed": round(missed_amount, 2),
        "fraud_amount_prevented": round(float(amount[y & flagged].sum()), 2),
        "estimated_total_cost": round(total_cost, 2),
        "incident_recall": round(incident_recall, 3),
        "recall_by_fraud_type": by_type,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def tune_weights(s: Scored, y_obs: np.ndarray, base: dict, tail: float, keys: list[str]) -> dict:
    best, best_w = -1.0, dict(base)
    grid = [dict(base)]
    for r in WEIGHT_GRID["rules"]:
        if keys == ["rules"]:
            grid.append({**base, "rules": r, "graph": 0.0, "anomaly": 0.0})
            continue
        for g in WEIGHT_GRID["graph"]:
            for a in WEIGHT_GRID["anomaly"]:
                grid.append({**base, "rules": r, "graph": g, "anomaly": a})
    for w in grid:
        ap = average_precision_score(y_obs, combine(s.model, s.rule_points, s.graph, s.anomaly_pct, w, tail))
        if ap > best:
            best, best_w = ap, w
    return best_w


def inference_latency(art: ModelArtifacts, frame: pd.DataFrame, n: int = 2000) -> dict:
    rows = frame.sample(n, random_state=3)
    x = rows[art.features].to_numpy(np.float32)
    xb = rows[BASE_FEATURES].to_numpy(np.float32)
    timings = {"supervised": [], "anomaly": []}
    for i in range(n):
        t0 = time.perf_counter_ns()
        art.supervised(x[i:i + 1])
        t1 = time.perf_counter_ns()
        art.anomaly_pct(xb[i:i + 1])
        t2 = time.perf_counter_ns()
        timings["supervised"].append((t1 - t0) / 1e6)
        timings["anomaly"].append((t2 - t1) / 1e6)
    return {k: {"p50_ms": round(float(np.percentile(v, 50)), 3), "p95_ms": round(float(np.percentile(v, 95)), 3),
                "p99_ms": round(float(np.percentile(v, 99)), 3)} for k, v in timings.items()}


def evaluate(customer: str, model_version: str, strategy_version: str, splits: Splits | None = None) -> dict:
    splits = splits or build_splits(customer)
    strategy = load_strategy(customer, strategy_version)
    art = ModelArtifacts(customer, model_version)
    budget = REVIEW_BUDGET[customer]
    tail = strategy["anomaly"]["tailStartPercentile"]

    va = splits.part("valid").reset_index(drop=True)
    te = splits.part("test").reset_index(drop=True)
    sv, st = score_frame(va, art, strategy), score_frame(te, art, strategy)
    yv = va["label_observed"].to_numpy()

    w_rules_ml = tune_weights(sv, yv, {"model": 1.0}, tail, ["rules"])
    w_full = tune_weights(sv, yv, {"model": 1.0}, tail, ["rules", "graph", "anomaly"])
    variants = {"rules_only": None, "lr_only": None, "ml_only": None,
                "hybrid_rules_ml": w_rules_ml, "hybrid_full": w_full}

    results = {}
    for name, weights in variants.items():
        v_score = variant_scores(sv, name, weights, tail)
        review_t, decline_t = choose_thresholds(v_score, yv, budget)
        t_score = variant_scores(st, name, weights, tail)
        # Rule-mandated minimum decisions (policies/emergency rules) apply to every variant that uses rules.
        min_rank = st.min_rank if name in ("rules_only", "hybrid_rules_ml", "hybrid_full") else 0
        rank = decide(t_score, np.full(len(te), review_t), np.full(len(te), decline_t), min_rank)
        results[name] = {"weights": weights, "thresholds": {"review": review_t, "decline": decline_t},
                         "test": operating_metrics(te, t_score, rank, budget)}

    # The strategy exactly as configured in the JSON file (what would be deployed).
    cfg_score = combine(st.model, st.rule_points, st.graph, st.anomaly_pct, strategy["weights"], tail)
    review_arr, decline_arr = thresholds_for(te, strategy)
    rank = decide(cfg_score, review_arr, decline_arr, st.min_rank)
    results["strategy_as_configured"] = {"weights": strategy["weights"], "thresholds": strategy["thresholds"],
                                         "test": operating_metrics(te, cfg_score, rank, budget)}

    rule_stats = {rid: {"hits_valid": int(sv.rule_hits[rid].sum()),
                        "precision_valid_observed": round(float(va.loc[sv.rule_hits[rid].to_numpy(), "label_observed"].mean()), 4)
                        if sv.rule_hits[rid].any() else None,
                        "hits_test": int(h.sum()),
                        "precision_test": round(float(te.loc[h.to_numpy(), "is_fraud"].mean()), 4) if h.any() else None}
                  for rid, h in st.rule_hits.items()}

    report = {
        "customer": customer, "modelVersion": model_version, "strategyVersion": strategy_version,
        "reviewBudget": budget, "declinePrecisionTarget": DECLINE_PRECISION, "costAssumptions": COST,
        "testWindow": {"from": str(te["event_time"].min()), "to": str(te["event_time"].max()),
                       "rows": int(len(te)), "fraud": int(te["is_fraud"].sum())},
        "variants": results, "ruleStats": rule_stats,
        "pythonInferenceLatency": inference_latency(art, te),
    }
    out = paths.REPORTS_DIR / customer
    out.mkdir(parents=True, exist_ok=True)
    stem = f"evaluation-{model_version}-strategy-{strategy_version}"
    (out / f"{stem}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (out / f"{stem}.md").write_text(to_markdown(report), encoding="utf-8")
    return report


def to_markdown(r: dict) -> str:
    cols = [("precision", "Precision"), ("recall", "Recall"), ("f1", "F1"), ("roc_auc", "ROC-AUC"),
            ("pr_auc", "PR-AUC"), ("false_positive_rate", "FPR"), ("false_negative_rate", "FNR"),
            ("precision_at_review_capacity", "P@capacity"), ("reviews_per_day", "Reviews/day"),
            ("declines_per_day", "Declines/day"), ("legit_flagged_per_1000_legit", "Legit flagged ‰"),
            ("fraud_amount_missed", "Fraud € missed"), ("estimated_total_cost", "Est. cost €"),
            ("incident_recall", "Incident recall")]
    lines = [f"# Evaluation — {r['customer']} — model {r['modelVersion']} — strategy {r['strategyVersion']}", "",
             "> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.",
             f"> Test: {r['testWindow']['rows']:,} transactions, {r['testWindow']['fraud']} fraud, "
             f"{r['testWindow']['from'][:10]} → {r['testWindow']['to'][:10]}.",
             f"> Review budget {r['reviewBudget']:.1%} of daily volume; decline precision target "
             f"{r['declinePrecisionTarget']:.0%}; costs: false decline €{r['costAssumptions']['false_decline']:.0f}, "
             f"review €{r['costAssumptions']['review']:.0f}, missed fraud = transaction amount.", "",
             "| Variant | " + " | ".join(c[1] for c in cols) + " |",
             "|---|" + "---|" * len(cols)]
    for name, v in r["variants"].items():
        m = v["test"]
        lines.append(f"| {name} | " + " | ".join(str(m[c[0]]) for c in cols) + " |")
    types = sorted({t for v in r["variants"].values() for t in v["test"]["recall_by_fraud_type"]})
    lines += ["", "## Recall by fraud type (transaction level)", "",
              "| Variant | " + " | ".join(types) + " |", "|---|" + "---|" * len(types)]
    for name, v in r["variants"].items():
        rb = v["test"]["recall_by_fraud_type"]
        lines.append(f"| {name} | " + " | ".join(str(rb.get(t, "-")) for t in types) + " |")
    lines += ["", "## Thresholds and weights (chosen on validation window)", "",
              "| Variant | Weights | Review | Decline |", "|---|---|---|---|"]
    for name, v in r["variants"].items():
        th = v["thresholds"]
        rv = th.get("review", th.get("default", {}).get("review"))
        dc = th.get("decline", th.get("default", {}).get("decline"))
        lines.append(f"| {name} | {v['weights']} | {rv} | {dc} |")
    lines += ["", "## Rule performance", "",
              "Validation precision uses labels known at training time (a lower bound); test uses ground truth.", "",
              "| Rule | Hits (valid) | Precision (valid, observed) | Hits (test) | Precision (test) |",
              "|---|---|---|---|---|"]
    for rid, st in r["ruleStats"].items():
        lines.append(f"| {rid} | {st['hits_valid']} | {st['precision_valid_observed']} | {st['hits_test']} | "
                     f"{st['precision_test']} |")
    lat = r["pythonInferenceLatency"]
    lines += ["", "## Single-row ONNX inference latency (Python onnxruntime, 1 thread, workbench container)", "",
              "| Model | p50 ms | p95 ms | p99 ms |", "|---|---|---|---|"]
    for k, v in lat.items():
        lines.append(f"| {k} | {v['p50_ms']} | {v['p95_ms']} | {v['p99_ms']} |")
    return "\n".join(lines) + "\n"

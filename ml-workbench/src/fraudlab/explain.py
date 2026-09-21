"""Explainability: SHAP contributions, reason codes, contrasting examples, FP/FN analysis.

SHAP values come from LightGBM's native TreeSHAP (``pred_contrib=True``) — exact for tree models and
identical to ``shap.TreeExplainer`` output in log-odds space, without the heavier dependency at
serving time. The same function is used by the Python model-service (``model_service/app.py``).
"""
from __future__ import annotations

import json

import lightgbm as lgb
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from . import paths
from .dataset import build_splits
from .evaluate import ModelArtifacts, score_frame
from .strategy import DECISIONS, combine, decide, load_strategy, thresholds_for
from .train import model_dir

# Model feature -> customer-facing reason code (shared with the Java service's ReasonCode enum).
FEATURE_REASON = {
    "card_txn_count_10m": "HIGH_TRANSACTION_VELOCITY", "card_txn_count_1h": "HIGH_TRANSACTION_VELOCITY",
    "device_txn_count_1h": "HIGH_TRANSACTION_VELOCITY", "account_txn_count_24h": "HIGH_TRANSACTION_VELOCITY",
    "is_new_device": "NEW_DEVICE", "has_device": "NEW_DEVICE",
    "ip_country_mismatch": "UNUSUAL_LOCATION", "is_foreign_merchant": "UNUSUAL_LOCATION",
    "card_country_change_1h": "UNUSUAL_LOCATION",
    "is_new_beneficiary": "NEW_BENEFICIARY", "beneficiary_foreign": "NEW_BENEFICIARY",
    "amount_to_baseline": "CUSTOMER_BEHAVIOR_DEVIATION", "account_amount_24h_to_baseline": "CUSTOMER_BEHAVIOR_DEVIATION",
    "amount_log": "CUSTOMER_BEHAVIOR_DEVIATION", "log_seconds_since_last": "CUSTOMER_BEHAVIOR_DEVIATION",
    "mcc_high_risk": "HIGH_RISK_MERCHANT", "hour_of_day": "UNUSUAL_TIME", "is_night": "UNUSUAL_TIME",
    "tenure_log": "CUSTOMER_PROFILE", "risk_tier_elevated": "CUSTOMER_PROFILE",
    "is_transfer": "TRANSACTION_CONTEXT", "is_ecom": "TRANSACTION_CONTEXT", "is_pos": "TRANSACTION_CONTEXT",
    "graph_device_risk": "GRAPH_RISK", "graph_beneficiary_risk": "GRAPH_RISK",
    "graph_merchant_risk": "GRAPH_RISK", "graph_account_risk": "GRAPH_RISK",
}


def contributions(booster: lgb.Booster, x: pd.DataFrame) -> pd.DataFrame:
    """Per-feature SHAP contributions (log-odds); the last column of pred_contrib is the bias."""
    contrib = booster.predict(x.to_numpy(np.float64), pred_contrib=True)
    return pd.DataFrame(contrib[:, :-1], columns=x.columns, index=x.index)


def top_reasons(contrib_row: pd.Series, features_row: pd.Series, k: int = 3) -> list[dict]:
    # Context features (channel/type flags) shape the score but are not actionable reasons.
    eligible = [f for f in contrib_row.index if FEATURE_REASON.get(f) != "TRANSACTION_CONTEXT"]
    row = contrib_row[eligible]
    pos = row[row > 0].sort_values(ascending=False).head(k)
    return [{"feature": f, "value": round(float(features_row[f]), 3), "contribution": round(float(c), 3),
             "reasonCode": FEATURE_REASON.get(f, "MODEL_SIGNAL")} for f, c in pos.items()]


def importance_chart(mean_abs: pd.Series, path, title: str) -> None:
    # Single series, magnitude job -> one hue (reference palette series-1), sorted horizontal bars,
    # recessive axes, direct labels only on the top three bars.
    data = mean_abs.sort_values().tail(12)
    fig, ax = plt.subplots(figsize=(7.5, 4.6), dpi=150)
    fig.patch.set_facecolor("#fcfcfb")
    ax.set_facecolor("#fcfcfb")
    ax.barh(data.index, data.values, height=0.55, color="#2a78d6")
    for i, (name, v) in enumerate(data.items()):
        if i >= len(data) - 3:
            ax.text(v, i, f"  {v:.2f}", va="center", fontsize=8, color="#52514e")
    ax.set_title(title, loc="left", fontsize=10, color="#0b0b0b")
    ax.set_xlabel("Mean |SHAP contribution| (log-odds), test window", fontsize=8, color="#52514e")
    ax.tick_params(colors="#52514e", labelsize=8, length=0)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color("#d9d8d4")
    ax.grid(axis="x", color="#ecebe8", linewidth=0.6)
    ax.set_axisbelow(True)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def _decisions(customer: str, model_version: str, strategy_version: str):
    splits = build_splits(customer)
    te = splits.part("test").reset_index(drop=True)
    strategy = load_strategy(customer, strategy_version)
    art = ModelArtifacts(customer, model_version)
    s = score_frame(te, art, strategy)
    score = combine(s.model, s.rule_points, s.graph, s.anomaly_pct, strategy["weights"],
                    strategy["anomaly"]["tailStartPercentile"])
    review, decline = thresholds_for(te, strategy)
    rank = decide(score, review, decline, s.min_rank)
    return te, s, score, rank, art


def _describe(te, s, score, rank, contrib, i) -> dict:
    row = te.loc[i]
    hits = [rid for rid, h in s.rule_hits.items() if h.iloc[i]]
    return {
        "transactionId": row["transaction_id"], "type": row["transaction_type"], "channel": row["channel"],
        "amount": float(row["amount"]), "segment": row["customer_segment"],
        "groundTruth": row["fraud_type"] if row["is_fraud"] else "legitimate",
        "decision": DECISIONS[int(rank[i])], "riskScore": round(float(score[i]), 3),
        "modelProbability": round(float(s.model[i]), 4), "anomalyPercentile": round(float(s.anomaly_pct[i]), 4),
        "graphRisk": round(float(s.graph[i]), 3), "rulePoints": float(s.rule_points[i]), "rulesHit": hits,
        "topModelReasons": top_reasons(contrib.loc[i], te.loc[i]),
    }


def _find_pair(te, rank, mask_type, used) -> tuple[int, int] | None:
    base = te[mask_type]
    flagged = base[(rank[base.index] >= 1) & base["is_fraud"]]
    approved = base[(rank[base.index] == 0) & ~base["is_fraud"]]
    for i, r in flagged.iterrows():
        if r["scenario_id"] in used:
            continue
        cand = approved[(approved["customer_segment"] == r["customer_segment"])
                        & ((approved["amount"] - r["amount"]).abs() <= 0.15 * r["amount"])]
        if len(cand):
            used.add(r["scenario_id"])
            return i, int(cand.index[0])
    return None


def explain(customer: str, model_version: str, strategy_version: str) -> dict:
    te, s, score, rank, art = _decisions(customer, model_version, strategy_version)
    booster = lgb.Booster(model_file=str(model_dir(customer, model_version) / "lightgbm.txt"))
    feats = te[art.features]
    sample_idx = te.sample(min(20000, len(te)), random_state=5).index
    contrib_all = contributions(booster, feats.loc[sample_idx])
    mean_abs = contrib_all.abs().mean()

    out = paths.REPORTS_DIR / customer
    out.mkdir(parents=True, exist_ok=True)
    importance_chart(mean_abs, out / "shap_importance.png", f"{customer} · {model_version} · global feature importance")

    # Explain every transaction we reference below.
    used: set = set()
    pairs = []
    for label, mask in (("Card payment (e-commerce)", (te["transaction_type"] == "CARD_PAYMENT") & (te["channel"] == "ECOM")),
                        ("Account-to-account transfer", te["transaction_type"] == "TRANSFER"),
                        ("Card payment (in store)", (te["transaction_type"] == "CARD_PAYMENT") & (te["channel"] == "POS"))):
        p = _find_pair(te, rank, mask, used)
        if p:
            pairs.append((label, p))
    y = te["is_fraud"].to_numpy()
    fp_idx = [int(i) for i in np.argsort(-score) if not y[i] and rank[i] >= 1][:3]
    fn_idx = [int(i) for i in np.argsort(score) if y[i] and rank[i] == 0][:3]
    needed = sorted({i for _, (a, b) in pairs for i in (a, b)} | set(fp_idx) | set(fn_idx))
    contrib = contributions(booster, feats.loc[needed])

    result = {
        "customer": customer, "modelVersion": model_version, "strategyVersion": strategy_version,
        "globalImportance": {k: round(float(v), 4) for k, v in mean_abs.sort_values(ascending=False).items()},
        "contrastingPairs": [{"label": lbl, "flagged": _describe(te, s, score, rank, contrib, a),
                              "approved": _describe(te, s, score, rank, contrib, b)} for lbl, (a, b) in pairs],
        "falsePositives": [_describe(te, s, score, rank, contrib, i) for i in fp_idx],
        "falseNegatives": [_describe(te, s, score, rank, contrib, i) for i in fn_idx],
    }
    (out / "explanations.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    (out / "explanations.md").write_text(_markdown(result), encoding="utf-8")
    return result


def _fmt_case(c: dict) -> str:
    reasons = ", ".join(f"{r['reasonCode']} ({r['feature']}={r['value']}, +{r['contribution']})"
                        for r in c["topModelReasons"]) or "none"
    return (f"| {c['transactionId']} | {c['groundTruth']} | **{c['decision']}** | {c['amount']:.2f} | "
            f"{c['riskScore']} | {c['modelProbability']} | {c['graphRisk']} | {c['anomalyPercentile']} | "
            f"{', '.join(c['rulesHit']) or '-'} | {reasons} |")


HEADER = ("| Transaction | Ground truth | Decision | Amount | Risk | Model p | Graph | Anomaly pct | Rules hit | Top model reasons (SHAP, log-odds) |\n"
          "|---|---|---|---|---|---|---|---|---|---|")


def _markdown(r: dict) -> str:
    lines = [f"# Explanations — {r['customer']} — model {r['modelVersion']} — strategy {r['strategyVersion']}", "",
             "> Synthetic data, test window. SHAP contributions are in log-odds; positive values push towards fraud.",
             "", "![Global importance](shap_importance.png)", "",
             "## Why two similar transactions received different decisions", ""]
    for p in r["contrastingPairs"]:
        lines += [f"### {p['label']}", "", HEADER, _fmt_case(p["flagged"]), _fmt_case(p["approved"]), ""]
    lines += ["## Highest-scoring false positives (genuine customers we flagged)", "", HEADER]
    lines += [_fmt_case(c) for c in r["falsePositives"]]
    lines += ["", "## Lowest-scoring false negatives (fraud we approved)", "", HEADER]
    lines += [_fmt_case(c) for c in r["falseNegatives"]]
    return "\n".join(lines) + "\n"

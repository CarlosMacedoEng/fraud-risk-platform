"""Reference implementation of the risk-strategy DSL and score combination (vectorised).

The Java ``StrategyEngine`` implements the same semantics per transaction; the strategy JSON files in
``config/customers/<customer>/strategies/`` are shared by both, so offline evaluation measures the
rules that are actually deployed.

Semantics:
* Leaf ``{"field", "op", "value"}``; a leaf on a missing (null) field is **false** for every operator.
* ``in_list`` / ``not_in_list`` reference a named list in ``strategy["lists"]``.
* ``action = SCORE`` adds ``points``; ``REVIEW``/``DECLINE`` set a minimum decision.
* Combined risk = noisy-OR: ``1 - Π(1 - w_i · s_i)`` over model, rules, graph and anomaly signals.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import paths

DECISIONS = ("APPROVE", "REVIEW", "DECLINE")
_RANK = {d: i for i, d in enumerate(DECISIONS)}


def load_strategy(customer: str, version: str) -> dict:
    path = paths.REPO_ROOT / "config" / "customers" / customer / "strategies" / f"{version}.json"
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _leaf(ctx: pd.DataFrame, cond: dict, lists: dict) -> pd.Series:
    field, op, value = cond["field"], cond["op"], cond.get("value")
    col = ctx[field]
    present = col.notna()
    if op == "eq":
        res = col == value
    elif op == "neq":
        res = col != value
    elif op in ("gt", "gte", "lt", "lte"):
        num = pd.to_numeric(col, errors="coerce")
        res = {"gt": num > value, "gte": num >= value, "lt": num < value, "lte": num <= value}[op]
    elif op == "between":
        num = pd.to_numeric(col, errors="coerce")
        res = (num >= value[0]) & (num <= value[1])
    elif op == "in":
        res = col.isin(value)
    elif op == "not_in":
        res = ~col.isin(value)
    elif op == "in_list":
        res = col.isin(lists.get(value, []))
    elif op == "not_in_list":
        res = ~col.isin(lists.get(value, []))
    else:
        raise ValueError(f"unknown operator {op}")
    return (present & res).astype(bool)


def evaluate_condition(ctx: pd.DataFrame, cond: dict, lists: dict) -> pd.Series:
    if "all" in cond:
        out = pd.Series(True, index=ctx.index)
        for c in cond["all"]:
            out &= evaluate_condition(ctx, c, lists)
        return out
    if "any" in cond:
        out = pd.Series(False, index=ctx.index)
        for c in cond["any"]:
            out |= evaluate_condition(ctx, c, lists)
        return out
    if "not" in cond:
        return ~evaluate_condition(ctx, cond["not"], lists)
    return _leaf(ctx, cond, lists)


def evaluate_rules(ctx: pd.DataFrame, strategy: dict) -> tuple[pd.Series, pd.Series, pd.DataFrame]:
    """Return (points, minimum decision rank, per-rule hit matrix)."""
    lists = strategy.get("lists", {})
    points = pd.Series(0.0, index=ctx.index)
    min_rank = pd.Series(0, index=ctx.index)
    hits = {}
    for rule in strategy.get("emergencyRules", []) + strategy.get("rules", []):
        if not rule.get("enabled", True):
            continue
        hit = evaluate_condition(ctx, rule["when"], lists)
        if "channels" in rule:
            hit &= ctx["channel"].isin(rule["channels"])
        hits[rule["id"]] = hit
        action = rule.get("action", "SCORE")
        if action == "SCORE":
            points += hit * float(rule.get("points", 0))
        else:
            min_rank = np.maximum(min_rank, hit * _RANK[action])
    return points, pd.Series(min_rank, index=ctx.index), pd.DataFrame(hits, index=ctx.index)


def anomaly_signal(percentile: np.ndarray, tail_start: float) -> np.ndarray:
    return np.clip((percentile - tail_start) / (1.0 - tail_start), 0.0, 1.0)


def combine(model: np.ndarray, rule_points: np.ndarray, graph: np.ndarray, anomaly_pct: np.ndarray,
            weights: dict, tail_start: float) -> np.ndarray:
    s_rules = np.clip(rule_points / 100.0, 0.0, 1.0)
    s_anom = anomaly_signal(anomaly_pct, tail_start)
    keep = ((1 - weights.get("model", 0.0) * model)
            * (1 - weights.get("rules", 0.0) * s_rules)
            * (1 - weights.get("graph", 0.0) * graph)
            * (1 - weights.get("anomaly", 0.0) * s_anom))
    return 1.0 - keep


def thresholds_for(ctx: pd.DataFrame, strategy: dict) -> tuple[np.ndarray, np.ndarray]:
    """Per-row (review, decline) thresholds: default, overridden by segment, then by channel."""
    t = strategy["thresholds"]
    review = np.full(len(ctx), t["default"]["review"], dtype=float)
    decline = np.full(len(ctx), t["default"]["decline"], dtype=float)
    for key, col in (("bySegment", "customer_segment"), ("byChannel", "channel")):
        for name, over in t.get(key, {}).items():
            mask = (ctx[col] == name).to_numpy()
            review[mask] = over.get("review", review[mask])
            decline[mask] = over.get("decline", decline[mask])
    return review, decline


def decide(score: np.ndarray, review_t: np.ndarray, decline_t: np.ndarray, min_rank: np.ndarray) -> np.ndarray:
    rank = np.where(score >= decline_t, 2, np.where(score >= review_t, 1, 0))
    return np.maximum(rank, np.asarray(min_rank))

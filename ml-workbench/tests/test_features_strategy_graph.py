import math

import numpy as np
import pandas as pd
import pytest

from fraudlab.features import HOUR, MIN_10, OnlineFeatureState, Profile
from fraudlab.graph import build_snapshot
from fraudlab.strategy import combine, decide, evaluate_condition, evaluate_rules

PROFILE = Profile(home_country="PT", avg_amount_90d=50.0, tenure_days=400, risk_tier="standard",
                  bound_device_ids=("D1",))
T0 = 1_767_225_600_000  # 2026-01-01T00:00:00Z


def ev(ts, **kw):
    base = {"ts": ts, "amount": 20.0, "customer_id": "C1", "card_token": "K1", "device_id": "D1",
            "beneficiary_id": None, "channel": "ECOM", "transaction_type": "CARD_PAYMENT",
            "merchant_country": "PT", "ip_country": "PT", "mcc": "5411", "beneficiary_country": None}
    base.update(kw)
    return base


# ---------------------------------------------------------------- features
def test_velocity_windows_exclude_current_event_and_expire():
    s = OnlineFeatureState()
    s.compute(ev(T0), PROFILE)
    s.compute(ev(T0 + 60_000), PROFILE)
    f = s.compute(ev(T0 + 120_000), PROFILE)
    assert f["card_txn_count_10m"] == 2 and f["card_txn_count_1h"] == 2
    f = s.compute(ev(T0 + MIN_10 + 60_001), PROFILE)          # first two now older than 10 min
    assert f["card_txn_count_10m"] == 1 and f["card_txn_count_1h"] == 3
    f = s.compute(ev(T0 + HOUR + 120_000), PROFILE)           # window boundary is exclusive
    assert f["card_txn_count_1h"] == 1


def test_bound_device_is_not_new_but_unknown_device_is():
    s = OnlineFeatureState()
    assert s.compute(ev(T0), PROFILE)["is_new_device"] == 0
    assert s.compute(ev(T0 + 1000, device_id="D9"), PROFILE)["is_new_device"] == 1
    assert s.compute(ev(T0 + 2000, device_id="D9"), PROFILE)["is_new_device"] == 0


def test_new_beneficiary_and_country_change():
    s = OnlineFeatureState()
    t = dict(transaction_type="TRANSFER", channel="MOBILE", card_token=None, merchant_country=None, mcc=None,
             beneficiary_id="B1", beneficiary_country="ES")
    f = s.compute(ev(T0, **t), PROFILE)
    assert f["is_new_beneficiary"] == 1 and f["beneficiary_foreign"] == 1
    assert s.compute(ev(T0 + 1000, **t), PROFILE)["is_new_beneficiary"] == 0
    s.compute(ev(T0 + 5000, channel="POS", device_id=None, ip_country=None), PROFILE)
    f = s.compute(ev(T0 + 30 * 60_000, channel="POS", device_id=None, ip_country=None, merchant_country="US"), PROFILE)
    assert f["card_country_change_1h"] == 1 and f["is_foreign_merchant"] == 1 and f["has_device"] == 0


def test_amount_and_time_features():
    s = OnlineFeatureState()
    f = s.compute(ev(T0 + 3 * HOUR, amount=500.0), PROFILE)
    assert f["amount_to_baseline"] == 10.0
    assert f["hour_of_day"] == 3 and f["is_night"] == 1
    assert math.isclose(f["amount_log"], math.log1p(500.0))
    assert math.isclose(f["log_seconds_since_last"], math.log1p(30 * 24 * 3600))


# ---------------------------------------------------------------- strategy DSL
CTX = pd.DataFrame({"amount": [10.0, 900.0, None], "channel": ["ECOM", "POS", "ECOM"],
                    "device_id": ["D1", "BAD", None], "is_new_device": [0, 1, 1]})


def test_null_field_is_false_for_every_operator():
    for op, value in (("eq", "x"), ("neq", "x"), ("not_in", ["x"]), ("gte", 1)):
        field = "device_id" if op in ("eq", "neq", "not_in") else "amount"
        assert not evaluate_condition(CTX, {"field": field, "op": op, "value": value}, {}).iloc[2]


def test_composite_conditions_and_lists():
    cond = {"all": [{"field": "amount", "op": "gte", "value": 100},
                    {"not": {"field": "channel", "op": "eq", "value": "ECOM"}}]}
    assert evaluate_condition(CTX, cond, {}).tolist() == [False, True, False]
    lst = {"field": "device_id", "op": "in_list", "value": "blockedDevices"}
    assert evaluate_condition(CTX, lst, {"blockedDevices": ["BAD"]}).tolist() == [False, True, False]


def test_rules_accumulate_points_and_minimum_decisions():
    strategy = {"lists": {"blockedDevices": ["BAD"]},
                "emergencyRules": [{"id": "E", "action": "DECLINE", "reasonCode": "BLOCKED_ENTITY",
                                    "when": {"field": "device_id", "op": "in_list", "value": "blockedDevices"}}],
                "rules": [{"id": "R1", "points": 30, "when": {"field": "is_new_device", "op": "eq", "value": 1}},
                          {"id": "R2", "points": 50, "enabled": False, "when": {"field": "amount", "op": "gte", "value": 0}}]}
    points, min_rank, hits = evaluate_rules(CTX, strategy)
    assert points.tolist() == [0, 30, 30]
    assert min_rank.tolist() == [0, 2, 0]
    assert "R2" not in hits


def test_noisy_or_combination_and_decision():
    s = combine(np.array([0.5]), np.array([100.0]), np.array([0.0]), np.array([0.5]),
                {"model": 1.0, "rules": 0.5, "graph": 1.0, "anomaly": 1.0}, 0.99)
    assert math.isclose(s[0], 1 - 0.5 * 0.5)
    rank = decide(np.array([0.1, 0.5, 0.9, 0.1]), np.full(4, 0.4), np.full(4, 0.8), np.array([0, 0, 0, 2]))
    assert rank.tolist() == [0, 1, 2, 2]


# ---------------------------------------------------------------- graph
def test_shared_device_and_mule_beneficiary_get_risk():
    t = pd.Timestamp("2026-01-10", tz="UTC")
    rows = []
    for i, c in enumerate(["C1", "C2", "C3", "C4"]):
        rows.append(dict(event_time=t - pd.Timedelta(hours=i + 1), customer_id=c, device_id="DSHARED",
                         ip_address=f"1.1.1.{i}", transaction_type="TRANSFER", beneficiary_id="MULE",
                         merchant_id=None, card_token=None, amount=500.0, is_fraud=i == 0,
                         label_available_at=t - pd.Timedelta(minutes=5) if i == 0 else pd.NaT))
    tx = pd.DataFrame(rows)
    snap = build_snapshot(tx, t, pd.Series(dtype="datetime64[ns, UTC]"))
    assert snap.device.loc["DSHARED", "risk"] == pytest.approx(0.6 * 0.75 + 0.4)
    assert snap.beneficiary.loc["MULE", "risk"] == pytest.approx(0.5 * 0.4 + 0.5)
    assert snap.account.loc["C2", "component_accounts"] == 4

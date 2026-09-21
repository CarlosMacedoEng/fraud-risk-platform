"""Online feature computation — the training/serving contract.

The Java decision service re-implements exactly this algorithm (``FeatureCalculator`` in
``decision-service``). A parity test replays the same event stream in both languages and compares
the resulting vectors, so any change here must be mirrored in Java and the feature-spec version bumped.

Semantics (identical in Java):
* Time is epoch milliseconds (UTC). Windows count **previously processed** events of the same key
  with ``ts > t - window`` (the current event is added after its features are computed).
* "Seen" sets (devices, beneficiaries per customer) are unbounded and seeded with the profile's
  bound devices.
* Missing values are encoded as 0 with an explicit presence flag (``has_device``).
"""
from __future__ import annotations

import math
from collections import defaultdict, deque
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .config import HIGH_RISK_MCC

FEATURE_SPEC_VERSION = "fs-1.0"

MIN_10 = 10 * 60 * 1000
HOUR = 60 * 60 * 1000
DAY = 24 * HOUR
SINCE_LAST_CAP_SECONDS = 30 * 24 * 3600

BASE_FEATURES: list[str] = [
    "amount_log",
    "amount_to_baseline",
    "hour_of_day",
    "is_night",
    "is_transfer",
    "is_ecom",
    "is_pos",
    "mcc_high_risk",
    "is_foreign_merchant",
    "ip_country_mismatch",
    "has_device",
    "is_new_device",
    "is_new_beneficiary",
    "beneficiary_foreign",
    "card_txn_count_10m",
    "card_txn_count_1h",
    "account_txn_count_24h",
    "account_amount_24h_to_baseline",
    "device_txn_count_1h",
    "log_seconds_since_last",
    "card_country_change_1h",
    "tenure_log",
    "risk_tier_elevated",
]

GRAPH_FEATURES: list[str] = [
    "graph_device_risk",
    "graph_beneficiary_risk",
    "graph_merchant_risk",
    "graph_account_risk",
]


@dataclass
class Profile:
    home_country: str
    avg_amount_90d: float
    tenure_days: int
    risk_tier: str
    bound_device_ids: tuple[str, ...] = ()


@dataclass
class OnlineFeatureState:
    """Per-key rolling state. Mirrors the Redis layout used by the Java feature store."""

    card_events: dict[str, deque] = field(default_factory=lambda: defaultdict(deque))      # ts
    account_events: dict[str, deque] = field(default_factory=lambda: defaultdict(deque))   # (ts, amount)
    device_events: dict[str, deque] = field(default_factory=lambda: defaultdict(deque))    # ts
    last_txn_ts: dict[str, int] = field(default_factory=dict)                              # customer -> ts
    last_card_country: dict[str, tuple[int, str]] = field(default_factory=dict)            # card -> (ts, country)
    seen_devices: dict[str, set] = field(default_factory=lambda: defaultdict(set))
    seen_beneficiaries: dict[str, set] = field(default_factory=lambda: defaultdict(set))

    @staticmethod
    def _count_after(q: deque, threshold: int, *, with_amount: bool = False) -> tuple[int, float]:
        # Entries are appended in time order, so we can drop expired entries from the left. The
        # longest window per key type is the one we evict for; shorter windows are counted by scan.
        count, total = 0, 0.0
        for item in reversed(q):
            ts = item[0] if with_amount else item
            if ts <= threshold:
                break
            count += 1
            if with_amount:
                total += item[1]
        return count, total

    @staticmethod
    def _evict(q: deque, threshold: int, *, with_amount: bool = False) -> None:
        while q and ((q[0][0] if with_amount else q[0]) <= threshold):
            q.popleft()

    def compute(self, ev: dict, profile: Profile) -> dict[str, float]:
        """Compute features for ``ev`` from prior state, then fold ``ev`` into the state."""
        t = int(ev["ts"])
        amount = float(ev["amount"])
        customer = ev["customer_id"]
        card = ev.get("card_token")
        device = ev.get("device_id")
        beneficiary = ev.get("beneficiary_id")
        channel = ev["channel"]
        is_transfer = ev["transaction_type"] == "TRANSFER"
        home = profile.home_country
        baseline = max(profile.avg_amount_90d, 1.0)

        seen_dev = self.seen_devices[customer]
        if not seen_dev and profile.bound_device_ids:
            seen_dev.update(profile.bound_device_ids)

        hour = (t // HOUR) % 24

        # velocity
        card_10m = card_1h = 0
        if card:
            q = self.card_events[card]
            self._evict(q, t - HOUR)
            card_1h, _ = self._count_after(q, t - HOUR)
            card_10m, _ = self._count_after(q, t - MIN_10)
        aq = self.account_events[customer]
        self._evict(aq, t - DAY, with_amount=True)
        acc_24h, acc_sum_24h = self._count_after(aq, t - DAY, with_amount=True)
        dev_1h = 0
        if device:
            dq = self.device_events[device]
            self._evict(dq, t - HOUR)
            dev_1h, _ = self._count_after(dq, t - HOUR)

        last = self.last_txn_ts.get(customer)
        since = SINCE_LAST_CAP_SECONDS if last is None else min(SINCE_LAST_CAP_SECONDS, (t - last) / 1000.0)

        country_change = 0
        merchant_country = ev.get("merchant_country")
        if card and merchant_country:
            prev = self.last_card_country.get(card)
            if prev is not None and t - prev[0] <= HOUR and prev[1] != merchant_country:
                country_change = 1

        ip_country = ev.get("ip_country")
        mcc = ev.get("mcc")
        b_country = ev.get("beneficiary_country")

        features = {
            "amount_log": math.log1p(amount),
            "amount_to_baseline": amount / baseline,
            "hour_of_day": float(hour),
            "is_night": 1.0 if hour < 6 else 0.0,
            "is_transfer": 1.0 if is_transfer else 0.0,
            "is_ecom": 1.0 if channel == "ECOM" else 0.0,
            "is_pos": 1.0 if channel == "POS" else 0.0,
            "mcc_high_risk": 1.0 if mcc in HIGH_RISK_MCC else 0.0,
            "is_foreign_merchant": 1.0 if merchant_country and merchant_country != home else 0.0,
            "ip_country_mismatch": 1.0 if ip_country and ip_country != home else 0.0,
            "has_device": 1.0 if device else 0.0,
            "is_new_device": 1.0 if device and device not in seen_dev else 0.0,
            "is_new_beneficiary": 1.0 if is_transfer and beneficiary not in self.seen_beneficiaries[customer] else 0.0,
            "beneficiary_foreign": 1.0 if is_transfer and b_country and b_country != home else 0.0,
            "card_txn_count_10m": float(card_10m),
            "card_txn_count_1h": float(card_1h),
            "account_txn_count_24h": float(acc_24h),
            "account_amount_24h_to_baseline": acc_sum_24h / baseline,
            "device_txn_count_1h": float(dev_1h),
            "log_seconds_since_last": math.log1p(since),
            "card_country_change_1h": float(country_change),
            "tenure_log": math.log1p(max(profile.tenure_days, 0)),
            "risk_tier_elevated": 1.0 if profile.risk_tier == "elevated" else 0.0,
        }

        # fold the event into state
        if card:
            self.card_events[card].append(t)
            if merchant_country:
                self.last_card_country[card] = (t, merchant_country)
        aq.append((t, amount))
        if device:
            self.device_events[device].append(t)
            seen_dev.add(device)
        if is_transfer and beneficiary:
            self.seen_beneficiaries[customer].add(beneficiary)
        self.last_txn_ts[customer] = t
        return features


def load_profiles(customers: pd.DataFrame) -> dict[str, Profile]:
    return {
        r.customer_id: Profile(
            home_country=r.home_country,
            avg_amount_90d=float(r.avg_amount_90d),
            tenure_days=int(r.tenure_days),
            risk_tier=r.risk_tier,
            bound_device_ids=tuple(d for d in (r.bound_device_ids or "").split(";") if d),
        )
        for r in customers.itertuples(index=False)
    }


def to_events(tx: pd.DataFrame) -> list[dict]:
    """Convert the transaction frame into plain event dicts (None for missing values)."""
    ev = tx[["transaction_id", "customer_id", "card_token", "device_id", "beneficiary_id", "channel",
             "transaction_type", "amount", "merchant_country", "ip_country", "mcc", "beneficiary_country"]].copy()
    ev["ts"] = tx["event_time"].astype("datetime64[ms, UTC]").astype("int64")
    ev = ev.astype(object).where(ev.notna(), None)
    return ev.to_dict("records")


def compute_features(tx: pd.DataFrame, customers: pd.DataFrame) -> pd.DataFrame:
    """Replay the whole stream in order and return the base feature matrix aligned with ``tx``."""
    profiles = load_profiles(customers)
    state = OnlineFeatureState()
    rows = [state.compute(ev, profiles[ev["customer_id"]]) for ev in to_events(tx)]
    return pd.DataFrame(rows, index=tx.index, columns=BASE_FEATURES).astype(np.float32)

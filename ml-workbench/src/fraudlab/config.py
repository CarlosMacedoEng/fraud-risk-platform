"""Generation profiles for the two fictional customers.

All numbers here are assumptions chosen to produce a plausible, learnable dataset. They are not
calibrated against any real institution. Country codes are arbitrary synthetic assignments and are
not a statement about the fraud risk of real countries.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timezone

# mcc -> (description, amount factor relative to the customer's baseline, risk band)
MCC_CATALOG: dict[str, tuple[str, float, str]] = {
    "5411": ("grocery", 0.7, "low"),
    "5812": ("restaurants", 0.6, "low"),
    "5541": ("fuel", 0.9, "low"),
    "4111": ("transport", 0.3, "low"),
    "5311": ("department_store", 1.4, "medium"),
    "5691": ("clothing", 1.2, "medium"),
    "5999": ("misc_retail", 1.0, "medium"),
    "4812": ("telecom", 0.8, "low"),
    "7011": ("hotels", 3.0, "medium"),
    "4511": ("airlines", 4.0, "medium"),
    "5732": ("electronics", 3.5, "high"),
    "5944": ("jewelry", 4.0, "high"),
    "5816": ("digital_goods", 0.5, "high"),
    "6051": ("quasi_cash_crypto", 2.5, "high"),
    "7995": ("gambling", 1.0, "high"),
    "8398": ("charity", 0.3, "medium"),
}

# Relative frequency of each MCC among ordinary merchants.
MCC_WEIGHTS: dict[str, float] = {
    "5411": 22, "5812": 18, "5541": 9, "4111": 8, "5311": 6, "5691": 7, "5999": 8, "4812": 3,
    "7011": 2, "4511": 1.5, "5732": 3, "5944": 1, "5816": 5, "6051": 0.8, "7995": 1.2, "8398": 1.5,
}

HIGH_RISK_MCC = frozenset(m for m, (_, _, band) in MCC_CATALOG.items() if band == "high")

# Countries where legitimate customers travel, and where fraud originates in this simulation.
TRAVEL_COUNTRIES = ["ES", "FR", "DE", "IT", "GB", "NL", "US", "BR", "TH"]
FRAUD_IP_COUNTRIES = ["RO", "VN", "NG", "UA", "ID", "BR", "US", "RU"]
DISTANT_COUNTRIES = ["US", "TH", "BR", "MX", "ID"]


@dataclass(frozen=True)
class Segment:
    weight: float
    daily_rate: float       # mean transactions per day
    avg_amount: float       # baseline ticket size in the profile currency
    card_share: float       # share of card payments vs transfers


@dataclass(frozen=True)
class FraudMix:
    stolen_card_victims: int
    ato_victims: int
    laundering_merchants: int
    rings: int
    ring_size_min: int
    ring_size_max: int
    card_testing_sessions: int
    geo_counterfeit_victims: int
    emerging_ato_victims: int      # drifted ATO variant appearing only after drift_start_fraction
    unreported_fraction: float     # ground-truth fraud that never receives a label
    laundering_daily_rate: float = 4.0  # purchases per day through each front merchant


@dataclass(frozen=True)
class GenerationProfile:
    customer: str
    id_prefix: str
    n_customers: int
    n_merchants: int
    days: int
    start: datetime
    currency: str
    home_countries: dict[str, float]
    segments: dict[str, Segment]
    card_channels: dict[str, float]
    transfer_channels: dict[str, float]
    travel_prob: float
    new_device_prob: float
    new_beneficiary_prob: float
    fraud: FraudMix
    drift_start_fraction: float = 0.8
    seed: int = 42

    def scaled(self, factor: float) -> "GenerationProfile":
        """Smaller copy for tests and quick runs; keeps at least one case per fraud typology."""
        f = self.fraud
        s = lambda n: max(1, int(round(n * factor)))  # noqa: E731
        return replace(
            self,
            n_customers=max(50, int(self.n_customers * factor)),
            n_merchants=max(60, int(self.n_merchants * factor)),
            fraud=replace(
                f,
                stolen_card_victims=s(f.stolen_card_victims),
                ato_victims=s(f.ato_victims),
                laundering_merchants=s(f.laundering_merchants),
                rings=s(f.rings),
                card_testing_sessions=s(f.card_testing_sessions),
                geo_counterfeit_victims=s(f.geo_counterfeit_victims),
                emerging_ato_victims=s(f.emerging_ato_victims),
            ),
        )


ALDERMOOR = GenerationProfile(
    customer="aldermoor-bank",
    id_prefix="ALD",
    n_customers=4000,
    n_merchants=1500,
    days=120,
    start=datetime(2026, 1, 1, tzinfo=timezone.utc),
    currency="EUR",
    home_countries={"PT": 1.0},
    segments={
        "student": Segment(0.15, 1.1, 18.0, 0.85),
        "retail": Segment(0.60, 1.3, 35.0, 0.75),
        "premium": Segment(0.15, 1.8, 90.0, 0.70),
        "business": Segment(0.10, 2.2, 150.0, 0.45),
    },
    card_channels={"POS": 0.65, "ECOM": 0.35},
    transfer_channels={"MOBILE": 0.60, "WEB": 0.25, "OPEN_BANKING": 0.10, "BRANCH": 0.05},
    travel_prob=0.15,
    new_device_prob=0.10,
    new_beneficiary_prob=0.10,
    fraud=FraudMix(
        stolen_card_victims=60,
        ato_victims=50,
        laundering_merchants=2,
        rings=4,
        ring_size_min=4,
        ring_size_max=7,
        card_testing_sessions=10,
        geo_counterfeit_victims=30,
        emerging_ato_victims=20,
        unreported_fraction=0.04,
        laundering_daily_rate=1.5,
    ),
)

QUILLON = GenerationProfile(
    customer="quillon-pay",
    id_prefix="QPY",
    n_customers=3500,
    n_merchants=1200,
    days=90,
    start=datetime(2026, 2, 1, tzinfo=timezone.utc),
    currency="EUR",
    home_countries={"ES": 0.3, "FR": 0.25, "DE": 0.2, "IT": 0.15, "PT": 0.1},
    segments={
        "casual": Segment(0.55, 0.9, 25.0, 0.92),
        "frequent": Segment(0.35, 2.0, 30.0, 0.88),
        "merchant_owner": Segment(0.10, 1.5, 80.0, 0.60),
    },
    card_channels={"ECOM": 0.92, "POS": 0.08},
    transfer_channels={"MOBILE": 0.85, "WEB": 0.15},
    travel_prob=0.10,
    new_device_prob=0.15,
    new_beneficiary_prob=0.18,
    fraud=FraudMix(
        stolen_card_victims=50,
        ato_victims=20,
        laundering_merchants=5,
        rings=3,
        ring_size_min=4,
        ring_size_max=6,
        card_testing_sessions=14,
        geo_counterfeit_victims=8,
        emerging_ato_victims=10,
        unreported_fraction=0.05,
    ),
)

PROFILES: dict[str, GenerationProfile] = {p.customer: p for p in (ALDERMOOR, QUILLON)}

# Days between the transaction and the label becoming available, per typology (min, max).
LABEL_DELAY_DAYS: dict[str, tuple[int, int]] = {
    "stolen_card": (5, 60),
    "account_takeover": (1, 10),
    "transaction_laundering": (20, 90),
    "fraud_ring": (10, 45),
    "card_testing": (3, 30),
    "geo_counterfeit": (2, 40),
}

"""Synthetic transaction generator.

Produces customers, merchants and a time-ordered transaction stream containing legitimate behaviour
(including deliberately "suspicious-looking" legitimate behaviour such as travel, new devices and new
beneficiaries) and six fraud typologies:

* stolen_card            - card-not-present burst on a victim's card from a new device/IP
* account_takeover       - new device + IP, transfers to a new or mule beneficiary
* transaction_laundering - front merchant processing illicit sales under a benign MCC
* fraud_ring             - mule accounts sharing devices/IPs, receiving ATO proceeds and cashing out
* card_testing           - bot device testing many cards with micro-payments (velocity)
* geo_counterfeit        - cloned card used card-present in a distant country (impossible travel)

Plus an "emerging" ATO variant that only appears after ``drift_start_fraction`` of the period, used
to discuss concept drift and the value of non-supervised signals.

Everything is deterministic for a given profile and seed.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from .config import (
    DISTANT_COUNTRIES,
    FRAUD_IP_COUNTRIES,
    HIGH_RISK_MCC,
    LABEL_DELAY_DAYS,
    MCC_CATALOG,
    MCC_WEIGHTS,
    TRAVEL_COUNTRIES,
    GenerationProfile,
)

TXN_COLUMNS = [
    "transaction_id", "event_time", "customer_id", "account_id", "card_token", "transaction_type",
    "channel", "amount", "currency", "merchant_id", "mcc", "merchant_country", "beneficiary_id",
    "beneficiary_country", "device_id", "ip_address", "ip_country", "is_fraud", "fraud_type",
    "scenario_id", "label_available_at",
]


@dataclass
class Customer:
    customer_id: str
    account_id: str
    segment: str
    home_country: str
    tenure_days: int
    age_band: str
    avg_amount: float
    daily_rate: float
    card_share: float
    pref_hour: float
    cards: list[str]
    devices: list[str]
    bound_devices: list[str]
    ips: list[str]
    beneficiaries: list[str]
    favourite_merchants: list[str]
    risk_tier: str = "standard"
    is_mule: bool = False
    ring_id: str | None = None


@dataclass
class Merchant:
    merchant_id: str
    mcc: str
    country: str
    created_at: datetime
    is_laundering_front: bool = False


@dataclass
class Dataset:
    customers: pd.DataFrame
    merchants: pd.DataFrame
    transactions: pd.DataFrame
    summary: dict = field(default_factory=dict)


class TransactionGenerator:
    def __init__(self, profile: GenerationProfile, seed: int | None = None):
        self.p = profile
        self.rng = np.random.default_rng(profile.seed if seed is None else seed)
        self.end = profile.start + timedelta(days=profile.days)
        self.drift_start = profile.start + timedelta(days=profile.days * profile.drift_start_fraction)
        self._counters: dict[str, int] = {}
        self.rows: list[dict] = []
        self.customers: list[Customer] = []
        self.merchants: list[Merchant] = []
        self.merchants_by_country: dict[str, list[Merchant]] = {}
        self.merchants_by_mcc: dict[str, list[Merchant]] = {}
        self.home_country_list = list(profile.home_countries)

    # ------------------------------------------------------------------ helpers
    def _id(self, kind: str) -> str:
        n = self._counters.get(kind, 0) + 1
        self._counters[kind] = n
        return f"{kind}{n:06d}"

    def _choice(self, items, weights=None):
        if weights is not None:
            w = np.asarray(weights, dtype=float)
            return items[self.rng.choice(len(items), p=w / w.sum())]
        return items[self.rng.integers(len(items))]

    def _weighted_key(self, mapping: dict):
        keys = list(mapping)
        return self._choice(keys, [mapping[k] for k in keys])

    def _ip(self, country: str) -> str:
        # Synthetic address; the country comes from a (simulated) geo-IP lookup, not from the prefix.
        first = 11 + (sum(ord(c) for c in country) * 7) % 180
        return f"{first}.{self.rng.integers(0, 256)}.{self.rng.integers(0, 256)}.{self.rng.integers(1, 255)}"

    def _amount(self, base: float, sigma: float = 0.55) -> float:
        return round(max(0.5, float(self.rng.lognormal(np.log(max(base, 0.5)), sigma))), 2)

    def _time_on_day(self, day_start: datetime, pref_hour: float, spread: float = 3.0) -> datetime:
        hour = float(np.clip(self.rng.normal(pref_hour, spread), 6.0, 23.9))
        if self.rng.random() < 0.03:  # occasional legitimate late-night activity
            hour = float(self.rng.uniform(0.0, 6.0))
        return day_start + timedelta(hours=hour, seconds=int(self.rng.integers(0, 60)))

    def _random_time(self, start: datetime | None = None, end: datetime | None = None) -> datetime:
        start = start or self.p.start + timedelta(days=3)
        end = end or self.end - timedelta(hours=12)
        span = (end - start).total_seconds()
        return start + timedelta(seconds=float(self.rng.uniform(0, span)))

    def _add(self, cust: Customer | None, t: datetime, *, ttype: str, channel: str, amount: float,
             card: str | None = None, merchant: Merchant | None = None, beneficiary: str | None = None,
             beneficiary_country: str | None = None, device: str | None = None, ip: str | None = None,
             ip_country: str | None = None, fraud_type: str | None = None, scenario: str | None = None,
             customer_id: str | None = None, account_id: str | None = None) -> None:
        if t >= self.end or t < self.p.start:
            return
        self.rows.append({
            "event_time": t,
            "customer_id": cust.customer_id if cust else customer_id,
            "account_id": cust.account_id if cust else account_id,
            "card_token": card,
            "transaction_type": ttype,
            "channel": channel,
            "amount": round(float(amount), 2),
            "currency": self.p.currency,
            "merchant_id": merchant.merchant_id if merchant else None,
            "mcc": merchant.mcc if merchant else None,
            "merchant_country": merchant.country if merchant else None,
            "beneficiary_id": beneficiary,
            "beneficiary_country": beneficiary_country,
            "device_id": device,
            "ip_address": ip,
            "ip_country": ip_country,
            "is_fraud": fraud_type is not None,
            "fraud_type": fraud_type,
            "scenario_id": scenario,
        })

    # ------------------------------------------------------------------ entities
    def _build_merchants(self) -> None:
        mccs = list(MCC_WEIGHTS)
        weights = [MCC_WEIGHTS[m] for m in mccs]
        countries = self.home_country_list + TRAVEL_COUNTRIES + DISTANT_COUNTRIES
        for i in range(self.p.n_merchants):
            # ~70% of merchants are in the customer's home market(s)
            if self.rng.random() < 0.7:
                country = self._weighted_key(self.p.home_countries)
            else:
                country = self._choice(countries)
            m = Merchant(self._id("M"), self._choice(mccs, weights), country,
                         self.p.start - timedelta(days=int(self.rng.integers(60, 3000))))
            self.merchants.append(m)
        # Guarantee coverage: every country has merchants in the MCCs fraud and travel scenarios use.
        for country in sorted(set(countries)):
            for mcc in ("5411", "5812", "5311", "5732", "5944", "5816", "8398", "7011"):
                self.merchants.append(Merchant(self._id("M"), mcc, country,
                                               self.p.start - timedelta(days=int(self.rng.integers(60, 3000)))))
        for m in self.merchants:
            self.merchants_by_country.setdefault(m.country, []).append(m)
            self.merchants_by_mcc.setdefault(m.mcc, []).append(m)

    def _merchant(self, country: str, mccs: set[str] | None = None) -> Merchant:
        pool = [m for m in self.merchants_by_country[country]
                if not m.is_laundering_front and (mccs is None or m.mcc in mccs)]
        return self._choice(pool)

    def _new_customer(self, segment: str, *, tenure: int | None = None, mule: bool = False) -> Customer:
        seg = self.p.segments[segment]
        home = self._weighted_key(self.p.home_countries)
        cid = f"{self.p.id_prefix}-{self._id('C')}"
        n_cards = 1 if self.rng.random() < 0.7 else 2
        n_dev = 1 if self.rng.random() < 0.6 else 2
        devices = [self._id("D") for _ in range(n_dev)]
        tenure = int(self.rng.integers(30, 3650)) if tenure is None else tenure
        local = [m for m in self.merchants_by_country[home] if not m.is_laundering_front]
        fav_idx = self.rng.choice(len(local), size=min(12, len(local)), replace=False)
        cust = Customer(
            customer_id=cid,
            account_id=f"{self.p.id_prefix}-{self._id('A')}",
            segment=segment,
            home_country=home,
            tenure_days=tenure,
            age_band=self._choice(["18-25", "26-35", "36-50", "51-65", "65+"], [15, 28, 30, 18, 9]),
            avg_amount=round(seg.avg_amount * float(self.rng.lognormal(0, 0.35)), 2),
            daily_rate=seg.daily_rate * float(self.rng.lognormal(0, 0.45)),
            card_share=seg.card_share,
            pref_hour=float(self.rng.normal(14, 2.5)),
            cards=[f"tok_{self._id('K')}" for _ in range(n_cards)],
            devices=devices,
            bound_devices=list(devices),
            ips=[self._ip(home) for _ in range(1 if self.rng.random() < 0.7 else 2)],
            beneficiaries=[self._id("B") for _ in range(int(self.rng.integers(1, 5)))],
            favourite_merchants=[local[i].merchant_id for i in fav_idx],
            is_mule=mule,
        )
        if tenure < 90:
            cust.risk_tier = "elevated"
        elif tenure > 1800 and segment in ("premium", "business"):
            cust.risk_tier = "low"
        return cust

    def _build_customers(self) -> None:
        segs = list(self.p.segments)
        weights = [self.p.segments[s].weight for s in segs]
        for _ in range(self.p.n_customers):
            self.customers.append(self._new_customer(self._choice(segs, weights)))

    # ------------------------------------------------------------------ legitimate behaviour
    def _simulate_legit(self, c: Customer, active_from: datetime | None = None) -> None:
        merchants_by_id = {m.merchant_id: m for m in self.merchants_by_country[c.home_country]}
        favourites = [merchants_by_id[m] for m in c.favourite_merchants]
        trip = None
        if self.rng.random() < self.p.travel_prob:
            t_start = int(self.rng.integers(5, self.p.days - 5))
            trip = (t_start, t_start + int(self.rng.integers(3, 11)),
                    self._choice([x for x in TRAVEL_COUNTRIES if x != c.home_country]))
        new_device_day = int(self.rng.integers(10, self.p.days)) if self.rng.random() < self.p.new_device_prob else None
        new_device = self._id("D") if new_device_day is not None else None
        trip_ip: dict[str, str] = {}

        for day in range(self.p.days):
            day_start = self.p.start + timedelta(days=day)
            if active_from and day_start < active_from:
                continue
            weekday_factor = 1.25 if day_start.weekday() >= 5 else 0.95
            n = self.rng.poisson(c.daily_rate * weekday_factor)
            abroad = trip is not None and trip[0] <= day < trip[1]
            location = trip[2] if abroad else c.home_country
            devices = list(c.devices)
            if new_device_day is not None and day >= new_device_day:
                if new_device not in c.devices:
                    c.devices.append(new_device)   # a legitimately new phone: not bound, not yet trusted
                devices = [new_device] * 4 + devices
            for _ in range(n):
                t = self._time_on_day(day_start, c.pref_hour)
                device = self._choice(devices)
                if abroad:
                    ip_country = location
                    ip = trip_ip.setdefault(location, self._ip(location))
                else:
                    ip_country, ip = c.home_country, self._choice(c.ips)
                if self.rng.random() < c.card_share:
                    channel = self._weighted_key(self.p.card_channels)
                    if abroad or self.rng.random() > 0.75:
                        merchant = self._merchant(location)
                    else:
                        merchant = self._choice(favourites)
                    factor = MCC_CATALOG[merchant.mcc][1]
                    amount = self._amount(c.avg_amount * factor)
                    if self.rng.random() < 0.01:   # occasional legitimate large purchase
                        amount = round(amount * float(self.rng.uniform(4, 10)), 2)
                    self._add(c, t, ttype="CARD_PAYMENT", channel=channel, amount=amount,
                              card=c.cards[0] if len(c.cards) == 1 or self.rng.random() < 0.8 else c.cards[1],
                              merchant=merchant,
                              device=device if channel == "ECOM" else None,
                              ip=ip if channel == "ECOM" else None,
                              ip_country=ip_country if channel == "ECOM" else None)
                else:
                    channel = self._weighted_key(self.p.transfer_channels)
                    if self.rng.random() < self.p.new_beneficiary_prob:
                        beneficiary = self._id("B")   # legitimately new beneficiary (rent, a friend...)
                        if self.rng.random() < 0.5:
                            c.beneficiaries.append(beneficiary)
                        b_country = c.home_country if self.rng.random() < 0.9 else self._choice(TRAVEL_COUNTRIES)
                    else:
                        beneficiary, b_country = self._choice(c.beneficiaries), c.home_country
                    self._add(c, t, ttype="TRANSFER", channel=channel,
                              amount=self._amount(c.avg_amount * 4, 0.7),
                              beneficiary=beneficiary, beneficiary_country=b_country,
                              device=None if channel == "BRANCH" else device,
                              ip=ip if channel in ("WEB", "MOBILE", "OPEN_BANKING") else None,
                              ip_country=ip_country if channel in ("WEB", "MOBILE", "OPEN_BANKING") else None)

    # ------------------------------------------------------------------ fraud typologies
    def _victims(self, n: int, exclude: set[str]) -> list[Customer]:
        pool = [c for c in self.customers if not c.is_mule and c.customer_id not in exclude]
        idx = self.rng.choice(len(pool), size=min(n, len(pool)), replace=False)
        chosen = [pool[i] for i in idx]
        exclude.update(c.customer_id for c in chosen)
        return chosen

    def _stolen_card(self, used: set[str]) -> None:
        for i, v in enumerate(self._victims(self.p.fraud.stolen_card_victims, used)):
            sid = f"STC-{i:04d}"
            t = self._random_time()
            device = self._id("D")
            ip_country = self._choice(FRAUD_IP_COUNTRIES) if self.rng.random() < 0.7 else v.home_country
            ip = self._ip(ip_country)
            card = self._choice(v.cards)
            for k in range(int(self.rng.integers(3, 13))):
                t += timedelta(minutes=float(self.rng.uniform(2, 40)))
                if k < 2 and self.rng.random() < 0.6:
                    merchant, amount = self._merchant(self._choice(self.home_country_list), {"5816", "8398"}), \
                        round(float(self.rng.uniform(1, 5)), 2)
                else:
                    merchant = self._merchant(self._choice(self.home_country_list + TRAVEL_COUNTRIES),
                                              set(HIGH_RISK_MCC) | {"5311"})
                    amount = round(float(self.rng.uniform(80, 900)), 2)
                self._add(v, t, ttype="CARD_PAYMENT", channel="ECOM", amount=amount, card=card,
                          merchant=merchant, device=device, ip=ip, ip_country=ip_country,
                          fraud_type="stolen_card", scenario=sid)

    def _account_takeover(self, used: set[str], rings: list[dict], *, emerging: bool) -> None:
        n = self.p.fraud.emerging_ato_victims if emerging else self.p.fraud.ato_victims
        for i, v in enumerate(self._victims(n, used)):
            sid = f"{'EMG' if emerging else 'ATO'}-{i:04d}"
            if emerging:
                t = self._random_time(start=self.drift_start)
            else:
                t = self._random_time(end=self.drift_start)
            ring = self._choice(rings) if rings and (emerging or self.rng.random() < 0.5) else None
            if ring and not emerging:
                device = self._choice(ring["devices"])
            else:
                device = self._id("D")
            if emerging:
                # "Low and slow": domestic IP, moderate amounts, mule beneficiaries reused across victims.
                ip_country = v.home_country
                amounts = [self._amount(v.avg_amount * float(self.rng.uniform(3, 6)), 0.2)
                           for _ in range(int(self.rng.integers(2, 4)))]
            else:
                ip_country = self._choice(FRAUD_IP_COUNTRIES) if self.rng.random() < 0.6 else v.home_country
                amounts = [self._amount(v.avg_amount * float(self.rng.uniform(8, 30)), 0.3)
                           for _ in range(int(self.rng.integers(1, 5)))]
            ip = self._choice(ring["ips"]) if ring and not emerging else self._ip(ip_country)
            if ring:
                beneficiary = self._choice(ring["mule_beneficiaries"])
                b_country = ring["home_country"]
            else:
                beneficiary, b_country = self._id("B"), self._choice(FRAUD_IP_COUNTRIES + [v.home_country])
            channel = self._choice(["MOBILE", "WEB"])
            for amount in amounts:
                t += timedelta(minutes=float(self.rng.uniform(3, 45)))
                self._add(v, t, ttype="TRANSFER", channel=channel, amount=amount, beneficiary=beneficiary,
                          beneficiary_country=b_country, device=device, ip=ip, ip_country=ip_country,
                          fraud_type="account_takeover", scenario=sid)

    def _build_rings(self) -> list[dict]:
        rings = []
        for r in range(self.p.fraud.rings):
            size = int(self.rng.integers(self.p.fraud.ring_size_min, self.p.fraud.ring_size_max + 1))
            home = self._weighted_key(self.p.home_countries)
            ring = {"ring_id": f"RING-{r:03d}", "home_country": home,
                    "devices": [self._id("D") for _ in range(2)],
                    "ips": [self._ip(home) for _ in range(2)],
                    "cashout_beneficiary": self._id("B"), "members": [], "mule_beneficiaries": []}
            segment = list(self.p.segments)[0]
            for _ in range(size):
                m = self._new_customer(segment, tenure=int(self.rng.integers(10, 120)), mule=True)
                m.ring_id = ring["ring_id"]
                m.devices = list(ring["devices"])
                m.bound_devices = list(ring["devices"])
                m.ips = list(ring["ips"])
                ring["members"].append(m)
                # The beneficiary id under which other customers pay into this mule account.
                ring["mule_beneficiaries"].append(f"B-{m.account_id}")
                self.customers.append(m)
            rings.append(ring)
        return rings

    def _ring_activity(self, rings: list[dict]) -> None:
        for ring in rings:
            for m in ring["members"]:
                active_from = self.p.start + timedelta(days=int(self.rng.integers(0, 20)))
                m.daily_rate = 0.3
                self._simulate_legit(m, active_from=active_from)   # thin cover activity
                for k in range(int(self.rng.integers(3, 8))):
                    t = self._random_time(start=active_from)
                    device, ip = self._choice(ring["devices"]), self._choice(ring["ips"])
                    if self.rng.random() < 0.6:
                        self._add(m, t, ttype="TRANSFER", channel="MOBILE",
                                  amount=round(float(self.rng.uniform(300, 2500)), 2),
                                  beneficiary=ring["cashout_beneficiary"],
                                  beneficiary_country=self._choice(FRAUD_IP_COUNTRIES),
                                  device=device, ip=ip, ip_country=ring["home_country"],
                                  fraud_type="fraud_ring", scenario=f"{ring['ring_id']}-{m.customer_id}-{k}")
                    else:
                        self._add(m, t, ttype="CARD_PAYMENT", channel="ECOM",
                                  amount=round(float(self.rng.uniform(150, 1200)), 2), card=m.cards[0],
                                  merchant=self._merchant(ring["home_country"], {"5732", "6051", "5944"}),
                                  device=device, ip=ip, ip_country=ring["home_country"],
                                  fraud_type="fraud_ring", scenario=f"{ring['ring_id']}-{m.customer_id}-{k}")

    def _card_testing(self, used: set[str]) -> None:
        for s in range(self.p.fraud.card_testing_sessions):
            sid = f"CTS-{s:04d}"
            device = self._id("D")
            ip_country = self._choice(FRAUD_IP_COUNTRIES)
            ip = self._ip(ip_country)
            t0 = self._random_time()
            for v in self._victims(int(self.rng.integers(3, 7)), used):
                card = self._choice(v.cards)
                t = t0 + timedelta(minutes=float(self.rng.uniform(0, 15)))
                for _ in range(int(self.rng.integers(2, 6))):
                    t += timedelta(seconds=float(self.rng.uniform(15, 120)))
                    self._add(v, t, ttype="CARD_PAYMENT", channel="ECOM",
                              amount=round(float(self.rng.uniform(0.5, 2.5)), 2), card=card,
                              merchant=self._merchant(self._choice(self.home_country_list + TRAVEL_COUNTRIES),
                                                      {"5816", "8398"}),
                              device=device, ip=ip, ip_country=ip_country, fraud_type="card_testing", scenario=sid)
                if self.rng.random() < 0.5:   # successful test -> cash-out a few hours later
                    self._add(v, t + timedelta(hours=float(self.rng.uniform(1, 8))), ttype="CARD_PAYMENT",
                              channel="ECOM", amount=round(float(self.rng.uniform(100, 600)), 2), card=card,
                              merchant=self._merchant(self._choice(self.home_country_list), {"5732", "6051"}),
                              device=device, ip=ip, ip_country=ip_country, fraud_type="card_testing", scenario=sid)

    def _geo_counterfeit(self, used: set[str]) -> None:
        for i, v in enumerate(self._victims(self.p.fraud.geo_counterfeit_victims, used)):
            sid = f"GEO-{i:04d}"
            day = self.p.start + timedelta(days=int(self.rng.integers(3, self.p.days - 1)))
            t = self._time_on_day(day, 12, 2)
            card = self._choice(v.cards)
            # Genuine domestic card-present payment by the real cardholder...
            self._add(v, t, ttype="CARD_PAYMENT", channel="POS", amount=self._amount(v.avg_amount * 0.7),
                      card=card, merchant=self._merchant(v.home_country, {"5411", "5812"}))
            # ...followed shortly by the cloned card abroad.
            country = self._choice([c for c in DISTANT_COUNTRIES if c != v.home_country])
            t += timedelta(minutes=float(self.rng.uniform(20, 90)))
            for _ in range(int(self.rng.integers(2, 6))):
                t += timedelta(minutes=float(self.rng.uniform(5, 30)))
                merchant = self._merchant(country, {"5732", "5944", "5311"})
                self._add(v, t, ttype="CARD_PAYMENT", channel="POS",
                          amount=self._amount(v.avg_amount * float(self.rng.uniform(3, 10)), 0.3),
                          card=card, merchant=merchant, fraud_type="geo_counterfeit", scenario=sid)

    def _transaction_laundering(self) -> None:
        prices = [19.90, 29.00, 49.00, 99.00, 149.00, 250.00]
        buyers = [c for c in self.customers if not c.is_mule]
        for i in range(self.p.fraud.laundering_merchants):
            home = self._weighted_key(self.p.home_countries)
            created = self.p.start + timedelta(days=int(self.rng.integers(0, max(1, self.p.days // 2))))
            front = Merchant(self._id("M"), self._choice(["5691", "5999"]), home, created, is_laundering_front=True)
            self.merchants.append(front)
            sid = f"TXL-{i:04d}"
            day = created
            while day < self.end:
                for _ in range(self.rng.poisson(self.p.fraud.laundering_daily_rate)):
                    b = self._choice(buyers)
                    hour = float(self.rng.uniform(0, 5)) if self.rng.random() < 0.55 else float(self.rng.uniform(5, 24))
                    self._add(b, day + timedelta(hours=hour), ttype="CARD_PAYMENT", channel="ECOM",
                              amount=self._choice(prices), card=b.cards[0], merchant=front,
                              device=self._choice(b.devices), ip=self._choice(b.ips), ip_country=b.home_country,
                              fraud_type="transaction_laundering", scenario=sid)
                day += timedelta(days=1)

    # ------------------------------------------------------------------ labels and assembly
    def _assign_labels(self, df: pd.DataFrame) -> pd.Series:
        label_at = pd.Series(pd.NaT, index=df.index, dtype="datetime64[us, UTC]")
        fraud_idx = df.index[df["is_fraud"]]
        for ftype, (lo, hi) in LABEL_DELAY_DAYS.items():
            idx = df.index[df["fraud_type"] == ftype]
            if len(idx) == 0:
                continue
            # One delay per scenario: a chargeback or complaint usually covers the whole incident.
            scen = df.loc[idx, "scenario_id"]
            delays = {s: float(self.rng.uniform(lo, hi)) for s in sorted(scen.unique())}
            delta = pd.to_timedelta(scen.map(delays), unit="D").dt.floor("s")
            label_at.loc[idx] = (df.loc[idx, "event_time"] + delta).astype("datetime64[us, UTC]")
        unreported = self.rng.random(len(fraud_idx)) < self.p.fraud.unreported_fraction
        label_at.loc[fraud_idx[unreported]] = pd.NaT
        return label_at

    def run(self) -> Dataset:
        self._build_merchants()
        self._build_customers()
        for c in self.customers:
            self._simulate_legit(c)
        used: set[str] = set()
        rings = self._build_rings()
        self._ring_activity(rings)
        self._account_takeover(used, rings, emerging=False)
        self._account_takeover(used, rings, emerging=True)
        self._stolen_card(used)
        self._card_testing(used)
        self._geo_counterfeit(used)
        self._transaction_laundering()

        df = pd.DataFrame(self.rows)
        df["event_time"] = pd.to_datetime(df["event_time"], utc=True).astype("datetime64[us, UTC]")
        df = df.sort_values(["event_time", "customer_id"], kind="stable").reset_index(drop=True)
        df.insert(0, "transaction_id", [f"{self.p.id_prefix}-T{i:09d}" for i in range(1, len(df) + 1)])
        df["label_available_at"] = self._assign_labels(df)
        df = df[TXN_COLUMNS]

        customers = pd.DataFrame([{
            "customer_id": c.customer_id, "account_id": c.account_id, "segment": c.segment,
            "home_country": c.home_country, "tenure_days": c.tenure_days, "age_band": c.age_band,
            "avg_amount_90d": c.avg_amount, "risk_tier": c.risk_tier,
            "card_tokens": ";".join(c.cards), "bound_device_ids": ";".join(c.bound_devices),
            "is_mule": c.is_mule, "ring_id": c.ring_id,
        } for c in self.customers])
        merchants = pd.DataFrame([{
            "merchant_id": m.merchant_id, "mcc": m.mcc, "mcc_description": MCC_CATALOG[m.mcc][0],
            "mcc_risk": MCC_CATALOG[m.mcc][2], "country": m.country,
            "created_at": m.created_at, "is_laundering_front": m.is_laundering_front,
        } for m in self.merchants])
        return Dataset(customers, merchants, df, summarize(df, self.p))


def summarize(df: pd.DataFrame, profile: GenerationProfile) -> dict:
    fraud = df[df["is_fraud"]]
    return {
        "customer": profile.customer,
        "seed": profile.seed,
        "period_start": profile.start.isoformat(),
        "days": profile.days,
        "transactions": int(len(df)),
        "fraud_transactions": int(len(fraud)),
        "fraud_rate": round(float(df["is_fraud"].mean()), 5),
        "fraud_by_type": {k: int(v) for k, v in fraud["fraud_type"].value_counts().sort_index().items()},
        "fraud_scenarios": int(fraud["scenario_id"].nunique()),
        "unlabelled_fraud": int(fraud["label_available_at"].isna().sum()),
        "by_type_and_channel": {f"{t}/{c}": int(n) for (t, c), n in
                                df.groupby(["transaction_type", "channel"]).size().items()},
        "fraud_amount_total": round(float(fraud["amount"].sum()), 2),
    }


def generate(profile: GenerationProfile, seed: int | None = None) -> Dataset:
    return TransactionGenerator(profile, seed).run()

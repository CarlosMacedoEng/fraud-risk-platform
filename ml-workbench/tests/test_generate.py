import pandas as pd
import pytest

from fraudlab.config import ALDERMOOR, QUILLON
from fraudlab.generate import TXN_COLUMNS, generate

FRAUD_TYPES = {"stolen_card", "account_takeover", "transaction_laundering", "fraud_ring",
               "card_testing", "geo_counterfeit"}


@pytest.fixture(scope="module")
def ds():
    return generate(ALDERMOOR.scaled(0.05))


def test_schema_and_ordering(ds):
    tx = ds.transactions
    assert list(tx.columns) == TXN_COLUMNS
    assert tx["transaction_id"].is_unique
    assert tx["event_time"].is_monotonic_increasing
    assert (tx["amount"] > 0).all()


def test_all_typologies_present(ds):
    assert set(ds.transactions["fraud_type"].dropna()) == FRAUD_TYPES


def test_class_imbalance_is_realistic(ds):
    rate = ds.transactions["is_fraud"].mean()
    assert 0.001 < rate < 0.05


def test_deterministic_for_same_seed():
    a = generate(QUILLON.scaled(0.03)).transactions
    b = generate(QUILLON.scaled(0.03)).transactions
    pd.testing.assert_frame_equal(a, b)


def test_labels_arrive_after_transaction(ds):
    tx = ds.transactions
    labelled = tx[tx["label_available_at"].notna()]
    assert labelled["is_fraud"].all()
    assert (labelled["label_available_at"] > labelled["event_time"]).all()


def test_card_payments_and_transfers_are_well_formed(ds):
    tx = ds.transactions
    cards, transfers = tx[tx.transaction_type == "CARD_PAYMENT"], tx[tx.transaction_type == "TRANSFER"]
    assert cards["card_token"].notna().all() and cards["merchant_id"].notna().all()
    assert transfers["beneficiary_id"].notna().all() and transfers["merchant_id"].isna().all()
    # Card-present payments carry no device or IP.
    assert cards.loc[cards.channel == "POS", "device_id"].isna().all()


def test_ring_members_share_devices(ds):
    ring = ds.transactions[ds.transactions.fraud_type == "fraud_ring"]
    per_device = ring.groupby("device_id")["customer_id"].nunique()
    assert per_device.max() >= 3


def test_emerging_pattern_only_after_drift_start(ds):
    tx = ds.transactions
    emerging = tx[tx["scenario_id"].fillna("").str.startswith("EMG")]
    p = ALDERMOOR
    drift_start = pd.Timestamp(p.start) + pd.Timedelta(days=p.days * p.drift_start_fraction)
    assert len(emerging) > 0
    assert (emerging["event_time"] >= drift_start).all()
    assert (emerging["ip_country"] == "PT").all()

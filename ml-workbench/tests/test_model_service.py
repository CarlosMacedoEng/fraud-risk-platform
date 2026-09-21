import json
import math
import os
from pathlib import Path

import pytest

os.environ.setdefault("ENABLE_FAULT_INJECTION", "true")
from fastapi.testclient import TestClient  # noqa: E402

from model_service.app import MODELS_DIR, app  # noqa: E402

CUSTOMER, VERSION = "aldermoor-bank", "aldermoor-bank-lgbm-1.0.0"
GOLDEN = MODELS_DIR / CUSTOMER / VERSION / "golden_scores.jsonl"
pytestmark = pytest.mark.skipif(not GOLDEN.exists(), reason="model artifacts not trained")


@pytest.fixture(scope="module")
def client():
    return TestClient(app)


def golden_rows(n=5):
    with Path(GOLDEN).open() as f:
        return [json.loads(next(f)) for _ in range(n)]


def test_score_matches_golden(client):
    for row in golden_rows():
        r = client.post("/v1/scores", json={"customerId": CUSTOMER, "modelVersion": VERSION, "features": row["features"]})
        assert r.status_code == 200
        assert math.isclose(r.json()["probability"], row["expectedProbability"], abs_tol=1e-6)


def test_explanation_contributions_sum_to_logit(client):
    row = golden_rows(1)[0]
    body = client.post("/v1/explanations", json={"customerId": CUSTOMER, "modelVersion": VERSION,
                                                 "features": row["features"]}).json()
    logit = body["baseValueLogOdds"] + sum(c["contribution"] for c in body["contributions"])
    assert math.isclose(1 / (1 + math.exp(-logit)), body["probability"], abs_tol=1e-4)
    assert len(body["topReasons"]) <= 3


def test_missing_features_is_422_with_contract_error(client):
    r = client.post("/v1/scores", json={"customerId": CUSTOMER, "modelVersion": VERSION, "features": {"amount_log": 1.0}})
    assert r.status_code == 422
    assert r.json()["detail"]["code"] == "MISSING_FEATURES"


def test_unknown_model_is_404(client):
    r = client.post("/v1/scores", json={"customerId": CUSTOMER, "modelVersion": "nope", "features": {}})
    assert r.status_code == 404


def test_correlation_id_is_propagated(client):
    r = client.get("/health", headers={"X-Correlation-Id": "abc-123"})
    assert r.headers["X-Correlation-Id"] == "abc-123"


def test_fault_injection_returns_503(client):
    client.post("/admin/faults", json={"latency_ms": 0, "error_rate": 1.0})
    try:
        row = golden_rows(1)[0]
        r = client.post("/v1/scores", json={"customerId": CUSTOMER, "modelVersion": VERSION, "features": row["features"]})
        assert r.status_code == 503
    finally:
        client.post("/admin/faults", json={"latency_ms": 0, "error_rate": 0.0})

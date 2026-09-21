"""Model service (Python / FastAPI) — explanations and challenger scoring.

Deliberately NOT on the synchronous scoring path (ADR-001). The Java decision service calls it:
* on demand, when an investigator opens a decision (``POST /v1/explanations``);
* optionally for shadow/challenger scoring comparisons (``POST /v1/scores``).

Fault injection (``POST /admin/faults``) exists so the troubleshooting lab can reproduce a slow or
failing model service (incident TS-11). It is disabled unless ``ENABLE_FAULT_INJECTION=true``.
"""
from __future__ import annotations

import json
import logging
import os
import time
import uuid
from functools import lru_cache
from pathlib import Path

import lightgbm as lgb
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from fraudlab.explain import FEATURE_REASON

MODELS_DIR = Path(os.environ.get("MODELS_DIR", Path(__file__).resolve().parents[2] / "models"))
FAULTS_ENABLED = os.environ.get("ENABLE_FAULT_INJECTION", "false").lower() == "true"

logging.basicConfig(level=logging.INFO, format='{"ts":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","msg":%(message)s}')
log = logging.getLogger("model-service")

app = FastAPI(title="Fraud model service", version="1.0.0")
_faults = {"latency_ms": 0, "error_rate": 0.0}


class FeaturesRequest(BaseModel):
    customerId: str = Field(..., examples=["aldermoor-bank"])
    modelVersion: str = Field(..., examples=["aldermoor-bank-lgbm-1.0.0"])
    features: dict[str, float]


class FaultConfig(BaseModel):
    latency_ms: int = 0
    error_rate: float = 0.0


class LoadedModel:
    def __init__(self, customer: str, version: str):
        directory = MODELS_DIR / customer / version
        if not (directory / "manifest.json").exists():
            raise FileNotFoundError(f"unknown model {customer}/{version}")
        self.manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        self.features: list[str] = self.manifest["features"]
        self.booster = lgb.Booster(model_file=str(directory / "lightgbm.txt"))
        self.session = ort.InferenceSession(str(directory / "supervised.onnx"), providers=["CPUExecutionProvider"])

    def vector(self, features: dict[str, float]) -> np.ndarray:
        missing = [f for f in self.features if f not in features]
        if missing:
            raise HTTPException(status_code=422, detail={"code": "MISSING_FEATURES", "missing": missing})
        return np.array([[features[f] for f in self.features]], dtype=np.float32)


@lru_cache(maxsize=16)
def model(customer: str, version: str) -> LoadedModel:
    return LoadedModel(customer, version)


def _get_model(req: FeaturesRequest) -> LoadedModel:
    try:
        return model(req.customerId, req.modelVersion)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail={"code": "MODEL_NOT_FOUND", "message": str(exc)}) from exc


@app.middleware("http")
async def correlation_and_faults(request: Request, call_next):
    correlation_id = request.headers.get("X-Correlation-Id", str(uuid.uuid4()))
    started = time.perf_counter()
    if FAULTS_ENABLED and request.url.path.startswith("/v1/"):
        if _faults["latency_ms"]:
            time.sleep(_faults["latency_ms"] / 1000)
        if _faults["error_rate"] and np.random.random() < _faults["error_rate"]:
            return JSONResponse({"code": "INJECTED_FAULT"}, status_code=503,
                                headers={"X-Correlation-Id": correlation_id})
    response = await call_next(request)
    elapsed = (time.perf_counter() - started) * 1000
    response.headers["X-Correlation-Id"] = correlation_id
    log.info(json.dumps({"event": "http_request", "path": request.url.path, "status": response.status_code,
                         "latency_ms": round(elapsed, 2), "correlation_id": correlation_id}))
    return response


@app.get("/health")
def health():
    return {"status": "UP", "modelsDir": str(MODELS_DIR), "faultInjection": FAULTS_ENABLED}


@app.get("/v1/models/{customer}")
def list_models(customer: str):
    reg = MODELS_DIR / customer / "registry.json"
    if not reg.exists():
        raise HTTPException(status_code=404, detail={"code": "CUSTOMER_NOT_FOUND"})
    return json.loads(reg.read_text(encoding="utf-8"))


@app.post("/v1/scores")
def score(req: FeaturesRequest):
    m = _get_model(req)
    prob = float(m.session.run(None, {"features": m.vector(req.features)})[1][0, 1])
    return {"modelVersion": req.modelVersion, "probability": prob}


@app.post("/v1/explanations")
def explain(req: FeaturesRequest):
    m = _get_model(req)
    x = m.vector(req.features)
    contrib = m.booster.predict(x.astype(np.float64), pred_contrib=True)[0]
    base_value, values = float(contrib[-1]), contrib[:-1]
    prob = float(m.session.run(None, {"features": x})[1][0, 1])
    items = sorted(({"feature": f, "value": float(x[0, i]), "contribution": round(float(values[i]), 5),
                     "reasonCode": FEATURE_REASON.get(f, "MODEL_SIGNAL")} for i, f in enumerate(m.features)),
                   key=lambda d: -abs(d["contribution"]))
    reasons = [d for d in items if d["contribution"] > 0 and d["reasonCode"] != "TRANSACTION_CONTEXT"][:3]
    return {"modelVersion": req.modelVersion, "probability": prob, "baseValueLogOdds": base_value,
            "method": "TreeSHAP (LightGBM pred_contrib), log-odds", "contributions": items, "topReasons": reasons}


@app.post("/admin/faults")
def set_faults(cfg: FaultConfig):
    if not FAULTS_ENABLED:
        raise HTTPException(status_code=403, detail={"code": "FAULT_INJECTION_DISABLED"})
    _faults.update(cfg.model_dump())
    log.warning(json.dumps({"event": "faults_updated", **_faults}))
    return _faults

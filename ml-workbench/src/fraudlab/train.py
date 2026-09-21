"""Train, export and register model versions.

Produces, per customer and version, an immutable directory ``models/<customer>/<model_version>/``:

* ``supervised.onnx``  LightGBM classifier (probability of fraud)
* ``anomaly.onnx``     Isolation Forest (raw anomaly score; mapped to a percentile via the manifest)
* ``baseline_lr.onnx`` logistic-regression baseline (evaluation only)
* ``lightgbm.txt``     native booster, used by the Python model-service for SHAP explanations
* ``manifest.json``    feature order, hashes, training window, label cut-off, metrics
* ``golden_scores.jsonl`` feature vectors + expected outputs for the Java parity test
"""
from __future__ import annotations

import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import lightgbm as lgb
import numpy as np
import onnxruntime as ort
import pandas as pd
import sklearn
from onnxmltools import convert_lightgbm
from onnxmltools.convert.common.data_types import FloatTensorType
from skl2onnx import to_onnx
from sklearn.ensemble import IsolationForest
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from . import paths
from .dataset import Splits, build_splits, label_coverage
from .features import BASE_FEATURES, FEATURE_SPEC_VERSION, GRAPH_FEATURES

SEED = 42
# Non-uniform grid: fine resolution in the tail, where the anomaly signal is actually used.
PERCENTILE_GRID = np.unique(np.concatenate([np.linspace(0, 0.9, 91), np.linspace(0.9, 0.99, 91),
                                            np.linspace(0.99, 1.0, 101)])).round(6)

FEATURE_SETS = {"base": BASE_FEATURES, "graph": BASE_FEATURES + GRAPH_FEATURES}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def model_dir(customer: str, version: str) -> Path:
    return paths.MODELS_DIR / customer / version


def anomaly_percentile(raw: np.ndarray, raw_quantiles: np.ndarray) -> np.ndarray:
    """Map raw anomaly scores to the training percentile (piecewise linear, same as Java)."""
    return np.interp(raw, raw_quantiles, PERCENTILE_GRID, left=0.0, right=1.0)


def train_supervised(splits: Splits, features: list[str]) -> lgb.LGBMClassifier:
    tr, va = splits.part("train"), splits.part("valid")
    model = lgb.LGBMClassifier(
        n_estimators=1000, learning_rate=0.05, num_leaves=31, min_child_samples=50,
        subsample=0.8, subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0,
        random_state=SEED, n_jobs=4, deterministic=True, force_row_wise=True, verbose=-1,
    )
    # No class re-weighting: it distorts probabilities. Imbalance is handled at threshold selection.
    model.fit(tr[features], tr["label_observed"].astype(int), eval_X=(va[features],),
              eval_y=(va["label_observed"].astype(int),), eval_metric="binary_logloss",
              callbacks=[lgb.early_stopping(100, first_metric_only=True, verbose=False)])
    # Log-loss stopping was chosen over average precision: with sparse delayed labels AP is noisy
    # (see experiments/early_stopping.py) and log-loss keeps probabilities better calibrated.
    return model


def train_lr(splits: Splits, features: list[str]):
    tr = splits.part("train")
    model = make_pipeline(StandardScaler(), LogisticRegression(class_weight="balanced", max_iter=2000, C=0.5))
    model.fit(tr[features].to_numpy(np.float32), tr["label_observed"].astype(int))
    return model


def train_anomaly(splits: Splits, features: list[str]) -> IsolationForest:
    tr = splits.part("train")
    sample = tr.sample(min(len(tr), 200_000), random_state=SEED)
    model = IsolationForest(n_estimators=150, max_samples=512, random_state=SEED, n_jobs=4)
    model.fit(sample[features].to_numpy(np.float32))
    return model


def _session(path: Path) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def export_and_register(customer: str, version: str, feature_set: str, splits: Splits | None = None) -> dict:
    splits = splits or build_splits(customer)
    features = FEATURE_SETS[feature_set]
    out = model_dir(customer, version)
    out.mkdir(parents=True, exist_ok=True)
    n = len(features)

    sup = train_supervised(splits, features)
    lr = train_lr(splits, features)
    iso = train_anomaly(splits, BASE_FEATURES)

    sup_onnx = convert_lightgbm(sup, initial_types=[("features", FloatTensorType([None, n]))],
                                zipmap=False, target_opset=15)
    (out / "supervised.onnx").write_bytes(sup_onnx.SerializeToString())
    sup.booster_.save_model(str(out / "lightgbm.txt"))
    lr_onnx = to_onnx(lr, splits.part("train")[features].to_numpy(np.float32)[:1],
                      options={id(lr): {"zipmap": False}}, target_opset=15)
    (out / "baseline_lr.onnx").write_bytes(lr_onnx.SerializeToString())
    iso_onnx = to_onnx(iso, splits.part("train")[BASE_FEATURES].to_numpy(np.float32)[:1],
                       target_opset={"": 15, "ai.onnx.ml": 3})
    (out / "anomaly.onnx").write_bytes(iso_onnx.SerializeToString())

    # --- verify the exported artifacts reproduce the in-memory models (evaluate what we ship)
    va = splits.part("valid")
    xv = va[features].to_numpy(np.float32)
    xa = va[BASE_FEATURES].to_numpy(np.float32)
    onnx_prob = _session(out / "supervised.onnx").run(None, {"features": xv})[1][:, 1]
    native_prob = sup.predict_proba(va[features])[:, 1]
    iso_sess = _session(out / "anomaly.onnx")
    iso_scores = iso_sess.run(None, {iso_sess.get_inputs()[0].name: xa})[1].ravel()
    native_iso = iso.decision_function(xa)
    parity = {"supervised_max_abs_diff": float(np.max(np.abs(onnx_prob - native_prob))),
              "anomaly_max_abs_diff": float(np.max(np.abs(iso_scores - native_iso)))}
    assert parity["supervised_max_abs_diff"] < 1e-4, parity
    assert parity["anomaly_max_abs_diff"] < 1e-3, parity

    # --- anomaly percentile calibration on the training window
    tr = splits.part("train")
    train_raw = -iso.decision_function(tr[BASE_FEATURES].to_numpy(np.float32))
    raw_quantiles = np.quantile(train_raw, PERCENTILE_GRID)
    raw_quantiles = np.maximum.accumulate(raw_quantiles)   # strictly usable for interpolation

    y_obs = va["label_observed"].to_numpy()
    y_true = va["is_fraud"].to_numpy()
    metrics = {
        "valid_observed_labels": {"pr_auc": float(average_precision_score(y_obs, onnx_prob)),
                                  "roc_auc": float(roc_auc_score(y_obs, onnx_prob))},
        "valid_ground_truth": {"pr_auc": float(average_precision_score(y_true, onnx_prob)),
                               "roc_auc": float(roc_auc_score(y_true, onnx_prob))},
        "best_iteration": int(sup.best_iteration_ or sup.n_estimators),
    }

    manifest = {
        "modelVersion": version,
        "customerId": customer,
        "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "featureSpecVersion": FEATURE_SPEC_VERSION,
        "featureSet": feature_set,
        "features": features,
        "supervised": {
            "file": "supervised.onnx", "sha256": _sha256(out / "supervised.onnx"),
            "inputName": "features", "probabilityOutput": "probabilities", "positiveClassIndex": 1,
            "algorithm": "LightGBM", "libraryVersion": lgb.__version__, "params": sup.get_params(),
        },
        "anomaly": {
            "file": "anomaly.onnx", "sha256": _sha256(out / "anomaly.onnx"),
            "inputName": iso_sess.get_inputs()[0].name, "scoreOutput": iso_sess.get_outputs()[1].name,
            "rawScore": "negated decision_function (higher = more anomalous)",
            "features": BASE_FEATURES, "algorithm": "IsolationForest", "libraryVersion": sklearn.__version__,
            "percentileGrid": PERCENTILE_GRID.tolist(), "rawQuantiles": raw_quantiles.round(8).tolist(),
        },
        "baseline": {"file": "baseline_lr.onnx", "sha256": _sha256(out / "baseline_lr.onnx"),
                     "algorithm": "LogisticRegression (standardised, class_weight=balanced)"},
        "training": {
            "labelCutoff": splits.label_cutoff.isoformat(),
            "boundaries": {k: v.isoformat() for k, v in splits.boundaries.items()},
            "labelCoverage": label_coverage(splits),
            "positivesObserved": int(tr["label_observed"].sum()),
            "seed": SEED, "python": platform.python_version(),
        },
        "exportParity": parity,
        "metrics": metrics,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str), encoding="utf-8")
    _write_golden(out, splits, features, sup_prob_session=_session(out / "supervised.onnx"),
                  iso_session=iso_sess, raw_quantiles=raw_quantiles)
    _update_registry(customer, version, manifest)
    return manifest


def _write_golden(out: Path, splits: Splits, features: list[str], *, sup_prob_session, iso_session,
                  raw_quantiles: np.ndarray, n: int = 300) -> None:
    te = splits.part("test")
    sample = pd.concat([te[te["is_fraud"]].sample(min(60, int(te["is_fraud"].sum())), random_state=1),
                        te[~te["is_fraud"]].sample(n - 60, random_state=1)])
    x = sample[features].to_numpy(np.float32)
    xa = sample[BASE_FEATURES].to_numpy(np.float32)
    prob = sup_prob_session.run(None, {"features": x})[1][:, 1]
    raw = -iso_session.run(None, {iso_session.get_inputs()[0].name: xa})[1].ravel()
    pct = anomaly_percentile(raw, raw_quantiles)
    with (out / "golden_scores.jsonl").open("w", encoding="utf-8") as f:
        for i, tid in enumerate(sample["transaction_id"]):
            f.write(json.dumps({"transactionId": tid,
                                "features": {k: float(v) for k, v in zip(features, x[i])},
                                "expectedProbability": float(prob[i]),
                                "expectedAnomalyRaw": float(raw[i]),
                                "expectedAnomalyPercentile": float(pct[i])}) + "\n")


def _update_registry(customer: str, version: str, manifest: dict) -> None:
    reg_path = paths.MODELS_DIR / customer / "registry.json"
    reg = json.loads(reg_path.read_text()) if reg_path.exists() else {"customerId": customer, "versions": []}
    reg["versions"] = [v for v in reg["versions"] if v["modelVersion"] != version]
    reg["versions"].append({
        "modelVersion": version, "featureSet": manifest["featureSet"], "createdAt": manifest["createdAt"],
        "status": "CANDIDATE", "validPrAuc": round(manifest["metrics"]["valid_ground_truth"]["pr_auc"], 4),
    })
    reg["versions"].sort(key=lambda v: v["modelVersion"])
    reg_path.write_text(json.dumps(reg, indent=2), encoding="utf-8")

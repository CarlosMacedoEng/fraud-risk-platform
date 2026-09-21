# ADR-001: In-process ONNX inference for the scoring hot path

- **Status:** Accepted (Stage 0) — to be validated by the Stage 8 performance test
- **Context:** The model is trained in Python (LightGBM, Isolation Forest). The decision service is Java
  and has a ~150 ms typical / 300 ms hard latency budget. Options: (a) call a Python scoring service over
  REST, (b) export models to ONNX and run them in-process with ONNX Runtime for Java, (c) re-implement
  the model in Java (e.g. PMML or hand-coded trees).
- **Decision:** (b). Models are exported to ONNX with a manifest (feature order, version, hash, metrics).
  The Java service loads a pinned version at startup and can hot-swap on `ModelVersionPromoted`.
  A separate Python `model-service` exists only for SHAP explanations and challenger/shadow scoring.
- **Consequences:**
  - (+) No network hop or extra failure domain on the hot path; inference is sub-millisecond to a few ms.
  - (+) Model version is part of the deployable/decision record; easy to audit.
  - (−) Feature engineering must be implemented identically in Python (training) and Java (serving) —
    **training/serving skew risk**. Mitigation: shared feature spec + a parity test that scores the same
    golden dataset in both languages and compares outputs.
  - (−) Not every Python model/library exports to ONNX; SHAP explanations are not available in-process.
  - (−) Model memory lives in every JVM replica.
- **When we would choose (a) instead:** frequent model changes by a separate data-science team, large
  models (deep learning, GPU), or models that cannot be exported.

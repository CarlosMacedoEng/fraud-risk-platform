# Demo Walkthrough — run it yourself, step by step

> Runs the whole platform locally and walks through seven scenarios in about 10 minutes. The recording in the
> README ([media/demo.gif](media/demo.gif)) and its full transcript ([media/demo-transcript.txt](media/demo-transcript.txt))
> come from exactly these steps, run with `scripts/record-demo.py`. Synthetic data, fictional customers.

## 0. Prerequisites
| Tool | Notes |
|---|---|
| Docker Desktop | ~8 GB RAM for the stack |
| JDK 21+ and Maven 3.9+ | to build the Java services |
| Python 3.10+ | the demo script uses it to format output |
| A bash shell | Linux/macOS terminal, or **Git Bash** on Windows (not WSL bash: it cannot reach Docker Desktop the same way) |

## 1. Build (≈ 3–5 min)
```bash
cd risk-platform && mvn -B -q package -DskipTests && cd ..
```
Optional: `mvn -B verify` runs all 96 tests (needs Docker for Testcontainers).

## 2. Start the stack (≈ 2 min)
```bash
DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml ps        # wait until decision-service is "healthy"
```
`DECISION_PROFILES=lab` enables the fault-injection endpoints used in step 6 of the demo. Never use it outside a lab.

## 3. Load reference data through the real integration path (≈ 1 min)
```bash
scripts/seed-demo.sh
```
Customer profiles, transaction history and labels go file → file-adapter → Kafka → consumers → PostgreSQL/Redis.

## 4. Warm up the JVM (2 min) — do not skip
A freshly started JVM is slow for minutes while the JIT compiles (measured: p95 ~330 ms cold vs 15 ms warm).
```bash
docker run --rm --network fraud-platform_default -v "$PWD/perf:/scripts" grafana/k6:1.3.0 run -q \
  -e SCENARIO=lab -e RATE=60 -e DURATION=120s -e BASE_URL=http://decision-service:8080 /scripts/scoring.js
```
(Git Bash on Windows: prefix with `MSYS_NO_PATHCONV=1` and use `"$(pwd -W)/perf:/scripts"`.)

## 5. Run the demo
```bash
scripts/demo.sh                 # press Enter between steps; DEMO_PAUSE=0 runs straight through
```

| Step | What happens | What you should see (from the recorded run) |
|---|---|---|
| 1. Real-time decision | A €2,450 transfer from a new device to a new foreign beneficiary is scored | `DECLINE`, reasons `HIGH_MODEL_SCORE, ANOMALOUS_PATTERN, NEW_BENEFICIARY, NEW_DEVICE, UNUSUAL_LOCATION`, versions (model, strategy, feature spec), ~12 ms |
| 2. Explanation | SHAP values from the Python model service | `is_new_device +3.27`, `ip_country_mismatch +2.62`, `amount_to_baseline +2.04` … |
| 3. Events and case | The decision's outbox events were published to Kafka; a moderate transfer goes to REVIEW and a case is created via the case-management API | 3 events `published = t`; `REVIEW → OPEN, HIGH, CM-…` |
| 4. Configuration change | Emergency rule blocks the device: author's self-approval rejected, second person approves, promoted, re-scored, rolled back | `403 (four-eyes)`, `200`, `DECLINE` with `BLOCKED_ENTITY`, `rollback: 200`, audit trail |
| 5. Legacy files | A valid core-banking file and a malformed one are ingested | `COMPLETED, 29 events` and `REJECTED / TOO_MANY_INVALID_RECORDS` |
| 6. Troubleshooting | The device-intelligence vendor becomes 250 ms slower under 30 req/s | client p99 ~68 ms (protected), ~54% decisions `DEVICE_RISK_UNAVAILABLE`, circuit `OPEN` |
| 7. Performance | Live server percentiles + recorded load-test results | p95/p99 of the last 5 minutes |

Numbers vary a little between runs; the decision in step 1 can differ if the synthetic velocity state has changed.

## 6. Record your own run (optional)
**Animated GIF from a real run (what the README uses):**
```bash
pip install pillow
python scripts/record-demo.py     # -> docs/media/demo.gif + docs/media/demo-transcript.txt
```
The recorder runs `scripts/demo.sh`, timestamps every output line and draws the GIF from that transcript. It refuses to
write a recording if the run had errors.

**Screen video:** Windows `Win + Alt + R` (Xbox Game Bar) or ScreenToGif; macOS `Cmd + Shift + 5`. Keep the file under
10 MB for GitHub, or upload an MP4 by dragging it into a GitHub issue/PR comment and pasting the generated link into the
README.

## 7. If something goes wrong
| Symptom | Fix |
|---|---|
| Step 2 shows `SHAP available: False` | model-service not ready yet: `docker compose ps model-service`, retry |
| Step 3 shows no case | the decision wasn't REVIEW this time, or consumers are not ready: wait 30 s and re-run |
| Step 6 shows no degraded decisions | decision-service not started with `DECISION_PROFILES=lab` |
| Everything slow | cold JVM: repeat step 4 |
| `python: command not found` (Windows) | you are in WSL bash; use Git Bash |

## 8. Stop
```bash
docker compose -f deploy/docker-compose.yml down        # add -v to also delete the database volume
```

# ML Workbench (Python)

Offline tooling for the Fraud Risk Platform: synthetic data generation, feature engineering, model
training, evaluation, explainability and export. It is **not** part of the synchronous scoring path
(see [ADR-001](../docs/adr/ADR-001-in-process-onnx-inference.md)).

Everything runs inside a pinned Python 3.12 image so results are reproducible regardless of the host
Python version.

```bash
docker build -t fraudlab-workbench:1.0 ml-workbench          # once
scripts/wb.sh python -m pytest -q                             # tests
scripts/wb.sh python -m fraudlab.cli generate --customer aldermoor-bank
scripts/wb.sh python -m fraudlab.cli generate --customer quillon-pay
```

On Git Bash for Windows prefix with `MSYS_NO_PATHCONV=1` so container paths are not rewritten.

## Stage 1 — Synthetic data generator

### Business purpose
A fraud platform cannot be configured, tested or demonstrated without transaction data that contains
both fraud and **realistic legitimate behaviour that looks suspicious**. False positives come from
genuine customers who travel, buy a new phone or pay a new beneficiary; a generator that only produces
"clean" legitimate traffic would make any model look better than it is.

### What is generated

| Output | Content |
|---|---|
| `data/generated/<customer>/transactions.parquet` | Time-ordered card payments and transfers with ground truth, fraud type, scenario ID and label-availability time |
| `data/generated/<customer>/customers.parquet` | Customer profile: segment, home country, tenure, baseline amount (`avg_amount_90d`), risk tier, bound devices |
| `data/generated/<customer>/merchants.parquet` | Merchants with MCC, country, onboarding date |
| `data/generated/<customer>/summary.json` | Volumes, fraud rate, counts per typology |
| `data/samples/<customer>/*.csv` | Small committed samples (used by docs and Java tests) |

Legitimate behaviour includes weekday/weekend patterns, preferred hours, favourite merchants,
occasional late-night activity, occasional large purchases, **travel abroad**, **legitimately new
devices** and **legitimately new beneficiaries**.

| Fraud typology | Pattern | Signals it should trigger |
|---|---|---|
| `stolen_card` | Card-not-present burst: small test payments, then high-risk MCCs, from a new device/foreign IP | new device, IP country mismatch, card velocity, high-risk MCC |
| `account_takeover` | New device + IP, 1–4 large transfers to a new or mule beneficiary within ~2 h | new device, new beneficiary, amount vs baseline |
| `transaction_laundering` | Recently onboarded front merchant with a benign MCC, round price points, night-time sales from many cards | merchant graph features (not visible per customer) |
| `fraud_ring` | Low-tenure mule accounts sharing 2 devices/IPs; receive ATO proceeds; cash out abroad | shared device degree, graph component risk |
| `card_testing` | One bot device tests 3–6 cards with micro-payments in minutes, then cash-out | device-level and card-level velocity |
| `geo_counterfeit` | Genuine domestic POS payment followed within ~1 h by POS payments in a distant country | impossible travel (country change within window) |
| *emerging ATO* (`EMG-*`) | Appears only in the last 20% of the period: domestic IP, moderate amounts, reused mule beneficiaries | designed to evade a model trained on earlier ATO; graph should still see mule reuse |

**Label delay.** Each scenario receives a label-availability time (e.g. chargebacks 5–60 days,
ATO complaints 1–10 days); ~4–5% of fraud is never reported. Training must only use labels that were
available at the training cut-off.

### Results of the current run (synthetic, seed 42)

| Customer | Days | Transactions | Fraud txns | Fraud rate | Scenarios |
|---|---|---|---|---|---|
| Aldermoor Bank | 120 | 779,675 | 1,321 | 0.17% | 280 |
| Quillon Pay | 90 | 476,434 | 2,207 | 0.46% | 191 |

Exact figures are in each `summary.json`.

### Engineering decisions
- **Deterministic** (`numpy.random.Generator` with fixed seed, sorted iteration) — tests assert identical output for the same seed.
- **Scenario IDs** group fraud transactions into incidents, so evaluation can report incident-level recall ("did we stop the attack?"), not only transaction-level.
- **Ground truth and observed labels are separate** (`is_fraud` vs `label_available_at`).
- **Card-present transactions have no device/IP**, as in reality; features must handle missing values.

### Limitations
- Behaviour is rule-generated; real fraud is adversarial and far more varied. Models trained here learn the generator's patterns.
- One account per customer; no balances, logins, authentication events or chargeback disputes.
- The emerging pattern was deliberately designed to be hard for the supervised model — conclusions about drift are illustrative.
- Fraud rate (0.17–0.46%) is higher than many real portfolios to keep enough positives for evaluation.

### Interview talking points
- "I generated suspicious-looking legitimate behaviour on purpose, because false positives are the main cost driver for a fraud operation."
- "Labels arrive late, so I modelled label delay and made training respect it — otherwise evaluation leaks future information."
- "I injected a drifted attack pattern in the final weeks to test whether non-supervised signals add value when the supervised model hasn't seen the pattern."

# Observability and Operations

> Implemented locally with Micrometer → Prometheus → Grafana (`deploy/`), structured JSON logs and
> correlation IDs. Production mapping: CloudWatch / Amazon Managed Prometheus & Grafana (AWS_AND_KUBERNETES.md).

## 1. Signals

### Logs
* JSON (Logstash encoder) to stdout, asynchronous non-blocking appender; MDC fields `correlationId`, `tenantId`,
  `clientId`, `transactionId`; one structured `decision` event per request with decision, score, versions,
  degraded modes, top reason and decision time.
* Never logged: request bodies, card tokens with PAN-like content, API keys, customer names (none exist).
* Correlation ID flows: gateway header → response header → logs → outbound HTTP headers → Kafka headers and
  event envelope → consumer MDC → case-management call. One ID reconstructs the whole story of a transaction.

### Metrics (selection; `/actuator/prometheus`)
| Metric | Meaning |
|---|---|
| `risk_decision_latency_seconds` (histogram, SLO buckets 50/100/250 ms) | server latency by tenant, decision, degraded |
| `risk_decisions_total{decision}` | decision mix (review rate = operations load) |
| `risk_decisions_degraded_total{mode}` | which signal was missing |
| `risk_model_inference_seconds`, `risk_model_failures_total{reason}` | model health |
| `integration_client_requests_seconds{integration,outcome}`, `integration_circuit_state` | every dependency |
| `resilience4j_circuitbreaker_*` | Redis breakers |
| `risk_featurestore_fallback_reads/rejected/skipped_writes_total` | Redis degradation |
| `risk_admission_rejected_total`, `risk_admission_inflight` | load shedding |
| `risk_outbox_backlog`, `risk_outbox_oldest_age_seconds` | event publication lag |
| `risk_cases_opened/dispatch_failures_total`, `risk_consumer_duplicates_total` | async integration |
| `hikaricp_connections_{active,pending,timeout}` | DB pool |
| `jvm_*`, `process_cpu_usage`, `executor_*{name="inference"}` | JVM and pools |
| `ingestion_files_total{type,status}`, `ingestion_records_total{outcome}`, `ingestion_scan_errors_total` | file adapter |

Tag policy: low cardinality only — never transaction, customer or card identifiers as tags.

### Traces
Not implemented (roadmap: Micrometer Tracing + OpenTelemetry → AWS X-Ray / Tempo). Correlation IDs give
cross-service reconstruction via logs today; spans would add per-dependency timing inside a single request.

## 2. Dashboards
`deploy/grafana/dashboards/fraud-platform.json` (provisioned, <http://localhost:3000>): Scoring (rate by
decision, p50/p95/p99, degraded modes, 5xx, model latency), Dependencies (outbound p95 and outcomes, circuit
states, feature-store fallback, timeouts, Hikari), Messaging & cases (outbox backlog/age, cases), JVM (heap,
GC, threads, inference pool), Files (by status, records by outcome).

## 3. SLOs (targets for a production deployment; measured values are local)
| SLI | SLO target | Local measurement |
|---|---|---|
| Scoring availability (non-5xx, excl. shed load) | 99.95% / 30 days | 0 × 5xx in all post-fix runs |
| Scoring latency p99 (server) | ≤ 250 ms | 63 ms at 150 TPS, warm |
| Degraded decision ratio | ≤ 1% daily | 0.54% at 150 TPS (baseline-06); 3.39% in baseline-08, 99.5% of it device-simulator timeouts (J-33, hypothesis) |
| Event publication lag | 99% < 5 s | outbox backlog ≤ 12 rows at 150 TPS |
| Case creation lag (REVIEW → case) | 99% < 60 s | seconds in tests |
| Inbound files processed by SLA time | 100% (06:00) | — |

Error budget policy: burning > 50% of the monthly budget freezes non-emergency strategy/model changes.

## 4. Alerts (`deploy/prometheus/alerts.yml`)
| Alert | Condition | Severity | Runbook |
|---|---|---|---|
| ScoringErrorRate | 5xx > 0.5% for 2 min | SEV1 | TS-02 / TS-16 |
| ModelUnavailable | any MODEL_UNAVAILABLE for 2 min | SEV1 | TS-11 |
| ScoringLatencyP95High | p95 > 100 ms for 5 min | SEV2 | TS-15 |
| DecisionsDegraded | > 5% degraded for 5 min | SEV2 | TS-12 |
| FeatureStoreFallback | any fallback read | SEV2 | TS-12 |
| OutboxLag | oldest event > 30 s | SEV2 | TS-07 |
| DbPoolPending | > 5 pending for 2 min | SEV2 | TS-02 |
| CircuitOpen | any integration open 1 min | SEV3 | TS-06 |
| ReviewVolumeSpike | REVIEW > 1% for 30 min | SEV3 | TS-14 |
| FileRejected / ScanErrors | any | SEV3 | TS-09 / TS-10 |
| **ReviewShareDrift** | REVIEW share over 15 min halved or doubled vs previous 3 h, for 10 min | SEV2 | TS-18 / TS-14 |
| **FeatureStoreWritesSkipped** | any skipped feature write | SEV3 | TS-12 (rebuild) |
| **CaseCreationStalled** | REVIEW decisions but no new case for 10 min | SEV2 | TS-07a |
| **ConsumerWithoutPartitions** | a consumer group member with 0 partitions for 5 min | SEV2 | TS-07a |
| **ConsumerRebalanceFailures** | > 3 failed rebalances in 10 min | SEV3 | TS-07a |
| **ConsumerLagHigh** | lag > 5,000 for 5 min | SEV3 | TS-07b |

The six rules in bold were added after the troubleshooting lab (journal J-25, J-32): those failures produced no
alert at all. `ConsumerRebalanceFailures` caught the recurrence of the broker fault two hours after it was added.
17 rules in total, validated with `promtool check rules`. Note: in the lab, `ReviewVolumeSpike` fires permanently
because the load-test pool inflates the REVIEW share (J-28); its 1% threshold is a placeholder for the customer's
agreed review budget.

Every alert names a runbook entry; alerts without an action are removed.

## 5. Severity model
| Sev | Definition | Response | Customer communication |
|---|---|---|---|
| SEV1 | Scoring unavailable or decisions wrong at scale (e.g. mass declines, model down) | 15 min, 24×7, incident commander | Initial update ≤ 30 min, then every 30 min |
| SEV2 | Degraded service within SLO risk (latency, fallback active, events delayed) | 30 min business hours / 1 h out of hours | ≤ 1 h, then every 2 h |
| SEV3 | Single integration or file issue, no customer-visible impact yet | next business day | daily summary |
| SEV4 | Question / cosmetic | backlog | via ticket |

## 6. Incident response procedure
1. **Detect & declare** (alert or report); assign incident commander and communicator; open timeline.
2. **Stabilise before diagnosing**: rollback strategy/model (one API call), disable a failing integration
   (property), shed load (admission limit), pause a consumer — the platform degrades by design.
3. **Diagnose** with the troubleshooting playbook: dashboards → logs by correlation ID → dependency metrics →
   JVM artefacts → database (`pg_stat_statements`, locks).
4. **Communicate** on the cadence above (template in CUSTOMER_COMMUNICATION.md).
5. **Resolve & verify** with the same signals that detected it.
6. **Post-incident review** within 5 business days: timeline, root cause, contributing factors, actions with
   owners and dates; feedback to product engineering if it is a product gap.

## 7. Operational procedures (daily/weekly)
* Daily: decision mix vs yesterday, degraded ratio, review queue size, file ingestion report (missing/rejected
  files), reconciliation critical categories (SETTLED_BUT_DECLINED, NOT_SCORED), DLT depth.
* Weekly: model drift indicators (score distribution, alert rate by segment), rule hit/precision table from
  labels, top slow queries, capacity headroom (CPU at peak ≤ 65%).
* Housekeeping jobs (roadmap to schedule): idempotency keys > 7 days, published outbox rows > 14 days,
  processed_events > 30 days.

## 8. Key takeaways
* "Every degradation is observable: a response field, a reason code, a metric and an alert — because in my
  own project graceful degradation hid two real bugs until I made it visible."
* "I alert on symptoms customers feel (errors, latency, degraded ratio, event lag) and every alert links to a
  runbook step."

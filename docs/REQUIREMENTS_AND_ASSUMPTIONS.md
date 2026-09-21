# Requirements and Assumptions (Phase 1 — Discovery)

> **Simulation notice.** The customer, people, volumes and systems in this document are fictional.
> They were invented to give the implementation a realistic shape. No real bank, customer data,
> or production system was involved. Any resemblance to real organisations is coincidental.

## 1. Fictional customers

The platform is implemented for one primary customer and configured for a second one, to show that
the same product supports different risk appetites through configuration rather than code changes.

### 1.1 Primary customer — Aldermoor Bank

| Attribute | Assumed value |
|---|---|
| Type | Mid-size retail bank, single-country with cross-border card usage |
| Customers | ~1.8M retail customers (assumption) |
| Products in scope | Debit/credit card payments (POS + e-commerce), account-to-account (A2A) instant transfers |
| Channels | Mobile app, web banking, card network (POS/CNP), open-banking API, branch-assisted transfers |
| Peak volume | ~250 TPS combined at peak, ~6M transactions/day (assumption) |
| Modern estate | Mobile/web back-ends on AWS (EKS), RDS PostgreSQL, a Kafka (MSK) programme in progress |
| Legacy estate | Core banking system exports **nightly fixed-layout/CSV files**; card processor sends **daily chargeback files**; customer master updated by batch |
| Fraud operations | 14 analysts, single shift + on-call; analyst queue ≈ 600 cases/day. REVIEW outcomes are handled first by automated customer confirmation (push/SMS), then by analysts. **Review budget used in evaluation: 0.3% of daily transactions** (assumption) |

### 1.2 Secondary customer — Quillon Pay

| Attribute | Assumed value |
|---|---|
| Type | Payment service provider / e-wallet for online merchants |
| Products in scope | Card-not-present payments, wallet-to-wallet transfers |
| Channels | Merchant API, mobile wallet |
| Risk profile | High volume, low ticket, merchant-driven fraud (transaction laundering), small review team |
| Fraud operations | Small team; strong preference for automated decisions. **Review budget used in evaluation: 0.2% of daily transactions** (assumption) |

## 2. Problem statement (Aldermoor Bank)

Aldermoor Bank's card and transfer fraud losses are rising, while its current rule set declines or
holds too many genuine payments. Rules were written separately per channel, so the same behaviour is
treated differently in mobile and web. Investigators work from spreadsheets exported from the core
system, so review is slow. Several upstream systems can only exchange files. Operations teams have
little visibility into why a decision was made or why latency spikes, and changing a rule currently
requires a code release.

**Target outcome:** one real-time decision service with a consistent, versioned, auditable risk
strategy across channels, combining rules and ML, integrated with modern and legacy systems, with
operational visibility and a safe change process.

## 3. Business requirements

| ID | Requirement | Priority |
|---|---|---|
| BR-01 | Return APPROVE / REVIEW / DECLINE for every card payment and A2A transfer in real time | Must |
| BR-02 | Every decision carries human-readable reason codes | Must |
| BR-03 | One risk strategy applies consistently across channels, with explicit channel policies where needed | Must |
| BR-04 | Risk analysts can change thresholds and rules without a code release, with approval and audit | Must |
| BR-05 | Review volume must stay within fraud-operations capacity | Must |
| BR-06 | REVIEW decisions automatically create an investigation case | Must |
| BR-07 | Confirmed fraud and chargebacks feed back into model training and reporting | Must |
| BR-08 | New strategies can be trialled on a percentage of traffic before full rollout | Should |
| BR-09 | Investigators can see why the model scored a transaction as risky | Should |
| BR-10 | Emergency rules (e.g. block a compromised BIN or device) can be activated within minutes | Should |
| BR-11 | Daily reconciliation between the decision log and core-banking settlement | Should |

## 4. Technical requirements

| ID | Requirement |
|---|---|
| TR-01 | REST scoring API, versioned (`/v1`), JSON, OpenAPI-documented |
| TR-02 | Idempotent scoring keyed by `Idempotency-Key` header + client ID |
| TR-03 | Correlation ID propagated through logs, outbound calls and events |
| TR-04 | Outbound REST integrations: customer profile, device/identity risk, case management |
| TR-05 | Event publication with at-least-once delivery and no loss if the broker is unavailable |
| TR-06 | File ingestion for transaction history, chargebacks, fraud labels, profile updates, settlement |
| TR-07 | PostgreSQL as system of record; schema managed by versioned migrations |
| TR-08 | Low-latency store for velocity counters and precomputed graph features |
| TR-09 | ML model executed with a bounded latency budget and a defined fallback |
| TR-10 | Health, readiness and Prometheus-format metrics endpoints |
| TR-11 | Container images; deployable to Kubernetes; mappable to AWS managed services |
| TR-12 | Configuration versioning with validation, promotion (dev → staging → prod) and rollback |

## 5. Non-functional requirements (targets, not results)

Targets are design goals. Measured results will be recorded separately with the environment they
were measured on. A laptop running Docker is **not** representative of production capacity.

| ID | Category | Target |
|---|---|---|
| NFR-01 | Latency | Decision service p95 ≤ 100 ms, p99 ≤ 250 ms at 150 TPS sustained, single instance, local synthetic load |
| NFR-02 | Hard deadline | Caller receives a decision (possibly degraded) within 300 ms; no hanging requests |
| NFR-03 | Availability | 99.95% monthly for the scoring API (production design target; not demonstrable locally) |
| NFR-04 | Durability | Zero loss of decisions/events during broker or downstream degradation (outbox pattern) |
| NFR-05 | Degradation | Model, Redis or downstream failure produces a documented fallback decision, never a 5xx for valid input |
| NFR-06 | Auditability | Every decision records strategy version, model version, inputs used and reason codes |
| NFR-07 | Security | Authenticated clients only; admin API separated by role; no PAN stored (token only) |
| NFR-08 | Operability | Every incident class in the troubleshooting playbook is detectable from logs/metrics |
| NFR-09 | Change safety | Strategy change can be rolled back in < 5 minutes without redeploy |

## 6. Integration inventory

| # | System (fictional) | Direction | Style | Data | Owner |
|---|---|---|---|---|---|
| I-01 | Payment gateway / channel back-ends | Inbound | REST (sync) | Transaction to score | Customer channels team |
| I-02 | Customer profile service | Outbound | REST (sync, cached) | Segment, tenure, home country, risk tier | Customer CRM team |
| I-03 | Device & identity risk provider | Outbound | REST (sync, strict timeout) | Device reputation, IP risk, emulator/proxy flags | Third-party vendor |
| I-04 | Case management system | Outbound | REST (async via event consumer) | Case for REVIEW decisions | Fraud operations |
| I-05 | Event backbone (Kafka / MSK) | Both | Events | Decisions, labels, config/model changes | Platform team |
| I-06 | Core banking | Inbound | Nightly file (CSV) | Transaction history, settlement | Core banking team |
| I-07 | Card processor | Inbound | Daily file (CSV) | Chargebacks | Cards operations |
| I-08 | Fraud operations | Inbound | File (JSON Lines) + events | Confirmed fraud labels | Fraud operations |
| I-09 | Customer master | Inbound | Daily file (JSON Lines) | Profile updates | Data team |
| I-10 | Reporting / data warehouse | Outbound | File (CSV) | Decision extract, reconciliation report | BI team |

## 7. Assumptions

1. The card authorisation path (ISO 8583) is **out of scope**; a gateway translates it to the REST scoring API.
2. PAN is tokenised upstream; the platform receives `cardToken` only.
3. Clients authenticate with an API key per client in the local build; production assumes OAuth2 client
   credentials or mTLS at the API gateway (documented, not implemented).
4. Labels (fraud confirmed / chargeback) arrive days to weeks after the transaction — label delay is modelled.
5. All data is synthetic and generated by the ML workbench with fixed random seeds.
6. Graph features are computed in near-real-time batch (minutes), not per-request graph traversal.
7. Customer-specific configuration is data, not code; platform code is shared across customers.

## 8. Constraints

- Local build must run on one developer machine with Docker Desktop.
- Java 21 language level (JDK 25 used locally), Spring Boot, Maven.
- Python only for data generation and model development; not in the synchronous scoring path.

## 9. Risk and dependency register

| ID | Risk / dependency | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-01 | Device-risk vendor latency spikes | Medium | High | 60 ms timeout, circuit breaker, "unknown device" fallback | Solutions engineer |
| R-02 | Label delay biases model evaluation | High | Medium | Time-based validation with label cut-off | Data scientist |
| R-03 | Legacy files arrive late or malformed | High | Medium | Validation, quarantine, reconciliation report, alerting on missing file | Integration engineer |
| R-04 | Rule changes cause false-positive spike | Medium | High | Validation, shadow/percentage rollout, rollback < 5 min | Risk analyst + SE |
| R-05 | Kafka programme not ready at go-live | Medium | Medium | Outbox table buffers events; relay resumes when broker available | Platform team |
| R-06 | Review capacity exceeded after go-live | Medium | High | Review-capacity-aware thresholds; daily review-volume monitoring | Fraud ops lead |
| R-07 | Synthetic data does not reflect real fraud | Certain (in this project) | High | Explicitly stated limitation; production requires customer historical data | — |

## 10. Open questions for the customer

1. What is the authorisation timeout budget the card processor gives us, end to end?
2. On timeout, should card payments fail open (approve) and A2A transfers fail to REVIEW?
3. Which channels require identical policies, and where are channel differences intentional?
4. What is the real daily review capacity, including weekends?
5. How are chargebacks and confirmed fraud linked back to the original transaction ID?
6. Which upstream systems can produce events today, and which only files?
7. Are there regulatory requirements on explaining declines to end customers?
8. What is the retention requirement for decisions and audit history?
9. Who approves strategy changes in production (four-eyes principle)?
10. What are the RPO/RTO expectations for the decision service?

# Project Purpose

## Why this project exists

This is a portfolio project that practises the work of a **customer success / solutions engineer** at a fraud and
financial-crime platform vendor. That work combines four things this project tries to practise end to end:

1. **Implementing** a real-time risk platform for a customer: integrations, configuration, data flows.
2. **Operating** it: performance, troubleshooting, incidents, upgrades.
3. **Working with the customer**: discovery, design decisions, status and risk reporting, go-live, handover.
4. **Raising the bar for the team**: reusable assets, standards, mentoring.

The scenario: a vendor's implementation team delivers a fraud risk platform to **Aldermoor Bank** (a fictional
mid-size retail bank) and configures the same platform for **Quillon Pay** (a fictional payment service provider)
to show that one product serves different risk appetites through configuration.

## What it is not

* **Not production software.** It runs on one laptop with Docker Compose (and a local kind cluster). It has
  not been deployed to a cloud, security-reviewed or tested with real traffic. See the limitations in each
  document.
* **Not real experience with a customer.** The customers, people, volumes and integrations are invented.
  There were no real banks, no real data and no real production incidents. Customer-facing documents are
  templates written as if for this fictional engagement.
* **Not a copy of any vendor's product.** The design is my own; it reflects publicly known patterns for
  real-time fraud detection (rules + ML + graph features, streaming integrations, human review).

## Honesty rules used throughout

| Rule | How it shows up |
|---|---|
| Measured vs target | Targets (NFRs) are labelled as targets; results name the environment they were measured on |
| Simulated vs real | Synthetic data and fictional customers are labelled everywhere; lab incidents are called "reproduced in a lab" |
| Implemented vs planned | Every document ends with limitations / future improvements; "not implemented" is stated explicitly |
| Failures are kept | Failed load tests, rejected hypotheses and wrong assumptions stay in the record ([perf/README.md](../perf/README.md), [ENGINEERING_JOURNAL.md](ENGINEERING_JOURNAL.md)) |
| Numbers are re-checked | Evidence files sit next to the claims; corrections are recorded, not silently fixed |

## How to read this repository

| If you want to see… | Start with |
|---|---|
| The problem and the value | [BUSINESS_CONTEXT.md](BUSINESS_CONTEXT.md) |
| The architecture | [ARCHITECTURE.md](ARCHITECTURE.md), [adr/](adr/) |
| ML and strategy decisions | [MODEL_STRATEGY.md](MODEL_STRATEGY.md), [CONFIGURATION_AND_RISK_STRATEGY.md](CONFIGURATION_AND_RISK_STRATEGY.md) |
| Real engineering problems and how they were found | [ENGINEERING_JOURNAL.md](ENGINEERING_JOURNAL.md) (39 entries) |
| Operations skills | [TROUBLESHOOTING_PLAYBOOK.md](TROUBLESHOOTING_PLAYBOOK.md), [JAVA_PERFORMANCE_GUIDE.md](JAVA_PERFORMANCE_GUIDE.md), [perf/README.md](../perf/README.md) |
| Delivery and customer work | [CUSTOMER_IMPLEMENTATION_PLAN.md](CUSTOMER_IMPLEMENTATION_PLAN.md), [GO_LIVE_RUNBOOK.md](GO_LIVE_RUNBOOK.md), [customer/](customer/) |
| Upgrades | [MIGRATION_AND_UPGRADE_RUNBOOK.md](MIGRATION_AND_UPGRADE_RUNBOOK.md) |
| Reusable assets and standards | [REUSABLE_ENGINEERING_ASSETS.md](REUSABLE_ENGINEERING_ASSETS.md), [MENTORING_AND_ENGINEERING_STANDARDS.md](MENTORING_AND_ENGINEERING_STANDARDS.md) |

## Success criteria for the project itself

* Every claim can be backed by a file in this repository.
* The difficult parts are explained with their trade-offs, including where the result was worse than expected.
* Someone else can reproduce the key results with the scripts provided (`perf/`, `troubleshooting-lab/`,
  `migration-rehearsal/`).

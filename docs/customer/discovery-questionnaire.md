# Discovery Questionnaire

> Template used in discovery workshops (fictional engagement: Aldermoor Bank). Answers in *italics* are the
> assumed answers recorded in REQUIREMENTS_AND_ASSUMPTIONS.md; items marked **open** stayed open questions.

## A. Business and risk appetite
1. Which products and channels are in scope for phase 1? *Cards (POS, e-commerce) and instant transfers.*
2. What are the current fraud losses by product and typology, and how are they measured? **Open.**
3. What does a false decline cost you (lost revenue, complaints, churn)? Who owns that number? **Open**, needed
   for threshold selection (see the Quillon €39 break-even example).
4. What is your review capacity per day, including weekends and peaks? *~600 cases/day, 14 analysts.*
5. Which typologies worry you most in the next 12 months? *Account takeover, scams on instant transfers, mules.*
6. Are there regulatory obligations to explain declines to customers? **Open.**
7. Who approves risk-policy changes, and is a four-eyes principle required? *Yes, four-eyes.*

## B. Current process
8. How are decisions made today (rules engine, vendor, manual)? How long does a rule change take?
9. How do analysts work cases today (tools, SLAs, outcomes recorded where)?
10. How are confirmed fraud and chargebacks linked back to the original transaction ID? **Open.**

## C. Integrations and data
11. For each channel: protocol, timeout budget end to end, behaviour on timeout (fail open / closed)?
    **Open** (see REQUIREMENTS §10, Q1–Q2).
12. Which systems can emit events today; which only files? Formats, schedules, volumes, owners.
13. Customer master data: which attributes, freshness, how updates arrive?
14. Device / identity intelligence vendor in use? Contract SLOs?
15. How much labelled history can you share (months, label types, delay)? Data residency constraints?

## D. Volumes and non-functionals
16. Peak TPS per channel, daily volumes, seasonality (payday, holidays). *~250 TPS peak, ~6M tx/day.*
17. Availability target, RPO/RTO, maintenance windows. **Open.**
18. Retention requirements for decisions, audit and model artefacts. **Open.**

## E. Infrastructure and security
19. Hosting model (vendor cloud / customer AWS account / on-prem)? Kubernetes platform and versions?
20. Network connectivity (PrivateLink, VPN), identity (OAuth2, mTLS), secret management.
21. Change management process (CAB, freeze periods), environments available (dev/test/staging/prod).

## F. Operations and people
22. Who operates the platform day to day? Existing on-call? Monitoring stack?
23. Who are the decision makers and the day-to-day contacts on each workstream?
24. What would make this project a success in 6 months, in your words?

## Output of discovery
Requirements document, integration inventory, open-question log with owners and dates, success criteria,
initial risk register, and a proposed plan (CUSTOMER_IMPLEMENTATION_PLAN.md).

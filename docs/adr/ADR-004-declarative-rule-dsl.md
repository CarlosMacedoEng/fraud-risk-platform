# ADR-004: Declarative JSON rule DSL for customer rules

- **Status:** Accepted (Stage 0)
- **Context:** Customers need to add and change rules without a release. Options: embedded scripting
  (Groovy/SpEL/JavaScript), a rules engine (Drools), or a small declarative DSL.
- **Decision:** A small JSON DSL: a rule is `{id, description, when: <condition tree>, then: {action, points,
  reasonCode}}` where conditions reference a **whitelisted set of fields** and operators
  (`eq, in, gt, gte, lt, lte, between, and, or, not`). Rules are validated on save (unknown fields,
  type mismatches, unreachable thresholds) and evaluated by a compiled predicate tree.
- **Consequences:**
  - (+) Safe: no arbitrary code execution in a payment path; easy to validate, diff and audit.
  - (+) Deterministic, fast evaluation (predicates compiled once per strategy version).
  - (−) Less expressive than a full engine; new field types require a platform release (deliberate).
- **Alternative:** Drools for large rule sets with complex chaining — unnecessary at this scope.

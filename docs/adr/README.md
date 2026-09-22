# Architecture decision records

These records capture the implemented decisions reviewed in Phase 18. “Accepted” records the repository's existing
architecture baseline; it does not mean cloud deployment or production launch has been approved. Alternatives are
reviewed design options, not claims that comparative benchmarks were performed.

| ADR | Decision |
| --- | --- |
| [001](001-runtime-boundaries.md) | GKE for the order API and continuous workers; Cloud Run for bounded push handlers |
| [002](002-transaction-and-delivery.md) | Transactional outbox/inbox and at-least-once delivery |
| [003](003-canonical-document-results.md) | Generation-pinned inputs and create-only canonical results |
| [004](004-identity-and-tenancy.md) | Tenant authorization plus separate workload, push, and signing identities |
| [005](005-data-and-module-ownership.md) | Separate service databases on shared SQL; narrow shared Java modules |
| [006](006-release-and-recovery.md) | Digest promotion, reviewed deployment, and coordinated recovery |

All records were reviewed on 2026-09-22. Supersede a decision with a new ADR when its assumptions change; preserve the
old decision and link its replacement. Open implementation and acceptance items are tracked in the
[architecture review](../architecture-review.md), rather than hidden in the decision status.

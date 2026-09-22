# Implementation roadmap

Work stops after each phase until the user replies `NEXT`.

1. **Architecture** — design agreed
2. **Repository** — scaffold implemented
3. **Domain & Database** — implemented; see [design and validation](domain-database.md)
4. **order-service** — implemented; see [API contract and validation](order-api.md)
5. **Pub/Sub & Reliable Messaging** — implemented; see [contracts, recovery, and validation](messaging.md)
6. **document-service** — implemented; see [processing, security, and recovery](document-processing.md)
7. **Cloud Storage** — implemented; see [adapters, signing, and validation](cloud-storage.md)
8. **notification-service** — implemented and integration-tested; see [consumer contract](notification-processing.md)
9. **Local Environment** — implemented and smoke-tested; see [startup and validation](local-environment.md)
10. **Testing** — implemented; see [layers, failure coverage, and validation](testing.md)
11. **GKE** — application chart and runtime support implemented; see [validation and prerequisites](gke.md); no cloud
    deployment performed
12. **Cloud Run** — runtime support and reference deployment contracts implemented; see [runbook](cloud-run.md); no cloud deployment performed
13. **Terraform** — platform, separate environment roots, state bootstrap, and mock tests implemented; see [runbook](terraform.md); no cloud apply performed
14. **Security** — request bounds, least-privilege IAM, and JWT regressions implemented; see [controls and validation](security.md)
15. **Resilience & Observability** — structured delivery telemetry, queue sampling, worker readiness, alert policies, and recovery runbooks implemented; see [operations](operations.md)
16. **CI/CD** — verification, scanned digest releases, and reviewed deployments implemented; see [setup and validation](cicd.md); no cloud deployment performed
17. **Production Engineering** — bounded load/restore drills, acknowledged-message replay retention, and launch acceptance runbook implemented; see [evidence and cloud prerequisites](production.md)
18. **Architecture Review** — invariants and failure boundaries reviewed, six ADRs recorded, module contracts added, and health-access regression fixed; see [findings and evidence](architecture-review.md)
19. **Interview Preparation** — project walkthrough, technical answer outlines, code tour, mock interview, failure scenarios, and evidence-based practice rubric implemented; see [guide](interview-preparation.md) and [practice](interview-practice.md)

All 19 planned repository phases are complete. Cloud deployment and production launch remain subject to the documented [acceptance gates](production.md#launch-evidence-and-operating-ownership); completion of this roadmap is not production certification.

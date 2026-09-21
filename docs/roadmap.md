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
14. **Security** — pending
15. **Resilience & Observability** — pending
16. **CI/CD** — pending
17. **Production Engineering** — pending
18. **Architecture Review** — pending
19. **Interview Preparation** — pending

Next: Phase 14 — Security.

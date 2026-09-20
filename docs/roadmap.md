# Implementation roadmap

Work stops after each phase until the user replies `NEXT`.

1. **Architecture** — design agreed
2. **Repository** — scaffold implemented
3. **Domain & Database** — implemented; see [design and validation](domain-database.md)
4. **order-service** — implemented; see [API contract and validation](order-api.md)
5. **Pub/Sub & Reliable Messaging** — implemented; see [contracts, recovery, and validation](messaging.md)
6. **document-service** — implemented; see [processing, security, and recovery](document-processing.md)
7. **Cloud Storage** — implemented; see [adapters, signing, and validation](cloud-storage.md)
8. **notification-service** — pending
9. **Local Environment** — pending
10. **Testing** — pending
11. **GKE** — pending
12. **Cloud Run** — pending
13. **Terraform** — pending
14. **Security** — pending
15. **Resilience & Observability** — pending
16. **CI/CD** — pending
17. **Production Engineering** — pending
18. **Architecture Review** — pending
19. **Interview Preparation** — pending

Next: Phase 8 — notification-service: authenticated event consumption, inbox deduplication, and durable audit/notification intent.

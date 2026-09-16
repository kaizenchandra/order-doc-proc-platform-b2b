# document-service

Phase 2 bootstrap only. No REST endpoints, database connections, cloud adapters, or deployment configuration exist yet.

Target: Cloud Run.

Implementation phase: 6.

Packages will grow around `api`, `application`, `domain`, `adapter`, and `config` only as concrete components are implemented. Flyway migrations belong to the service that owns the database; document-service has no SQL dependency.

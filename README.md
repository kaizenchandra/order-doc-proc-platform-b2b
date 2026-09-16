# Order Document Processing Platform

Java 21 / Spring Boot 4.1.1 / Spring Framework 7, built incrementally as a production architecture exercise.

**Current milestone: Phase 4 — order-service workflows and HTTP API.** Order-service exposes authenticated
order/document endpoints with transactional outbox writes. Real GCS operations, event publication, and document
processing arrive in later phases. Infrastructure directories are reserved structure, not deployment-ready resources.

## Build

Use JDK 21 and the checked-in Maven wrapper (Maven 3.9.16):

```sh
./mvnw -B -ntp verify
./mvnw -B -ntp -pl services/order-service -am verify
```

On macOS, if `JAVA_HOME` is invalid, scope the correction to the command:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B -ntp verify
```

Import the root `pom.xml` in IntelliJ. Do not import the five child POMs as unrelated projects. Configure the project
SDK and Maven runner to JDK 21.

Surefire runs `*Test` / `*Tests`; Failsafe runs `*IT` / `*ITCase` during `verify`. PostgreSQL integration tests now
require a running Docker engine. No cloud credentials are needed for this milestone.

## Repository

```text
.
├── pom.xml                         # Parent and five-module reactor
├── mvnw / mvnw.cmd / .mvn/         # Pinned Maven distribution
├── HELP.md                         # Original generated reference
├── services/
│   ├── order-service/              # GKE; owns order DB, outbox, result inbox
│   ├── document-service/           # Cloud Run; GCS processing, no SQL
│   └── notification-service/       # Cloud Run; owns inbox and audit DB
├── shared/
│   ├── event-contracts/            # Plain JAR, versioned wire contracts
│   └── common-observability/       # Plain JAR, telemetry support
├── infrastructure/
│   ├── terraform/
│   │   ├── environments/{dev,staging,prod}/
│   │   └── modules/{project-services,network,gke,cloud-run,cloud-sql,
│   │               pubsub,storage,artifact-registry,iam,secret-manager}/
│   ├── kubernetes/                 # Prerequisites
│   └── helm/order-service/         # Application release
├── scripts/                       # Local emulator init and operational scripts later
├── docs/
│   ├── architecture.md
│   ├── repository.md
│   ├── roadmap.md
│   └── adr/                       # Formal ADRs in Phase 18
└── .github/workflows/              # CI/CD in Phase 16
```

Dockerfiles, Compose, Helm templates, Terraform resources, CI workflows, and environment-specific application profiles
will be added in their designated phases; empty runnable-looking configuration is intentionally avoided.

## Design and progress

- [Architecture baseline](docs/architecture.md)
- [Build and module decisions](docs/repository.md)
- [Domain, schema, and transaction design](docs/domain-and-database.md)
- [Domain and database decisions](docs/domain-database.md)
- [Order API, authentication, and retry contract](docs/order-api.md)
- [Phase roadmap](docs/roadmap.md)
- [Infrastructure ownership](infrastructure/README.md)

Never commit service-account keys, real credentials, signed URLs, or Terraform state. Git ignore rules reduce accidents
but are not a secret scanner.

# Order Document Processing Platform

Java 21 / Spring Boot 4.1.1 / Spring Framework 7, built incrementally as a production architecture exercise.

**Current milestone: Phase 10 — Testing.** Order-service exposes authenticated order/document endpoints,
a leased outbox relay, an idempotent result consumer, and signed upload/report authorization. Document-service adds
authenticated push handling, PDF metadata processing, generation-pinned GCS reads, create-only canonical reports,
and stable result publication. Notification-service consumes authenticated events and atomically records inbox, audit,
and notification intent. Messaging and GCS adapters are opt-in. Cloud infrastructure directories remain reserved
structure, not deployment-ready resources.

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

## Run locally

With JDK 21, Docker Compose, and Python 3:

```sh
./scripts/local/up.sh
```

This starts the services and emulators and runs an end-to-end smoke check. See the
[local environment guide](docs/local-environment.md) for endpoints, credentials, and reset/recovery behavior.

Run all test layers, including emulator workflow regressions, with `./scripts/local/test.sh`.

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
├── scripts/                       # Local startup, issuer/bridge, and smoke check
├── docs/
│   ├── architecture.md
│   ├── repository.md
│   ├── roadmap.md
│   └── adr/                       # Formal ADRs in Phase 18
└── .github/workflows/              # CI/CD in Phase 16
```

Dockerfiles, Compose, and explicit local storage profiles now support a complete local workflow.
Helm templates, Terraform resources, CI workflows, and cloud profiles arrive in their designated phases.

## Design and progress

- [Architecture baseline](docs/architecture.md)
- [Build and module decisions](docs/repository.md)
- [Domain, schema, and transaction design](docs/domain-and-database.md)
- [Domain and database decisions](docs/domain-database.md)
- [Order API, authentication, and retry contract](docs/order-api.md)
- [Messaging contracts, recovery, and validation](docs/messaging.md)
- [Document processing, push authentication, and recovery](docs/document-processing.md)
- [Cloud Storage adapters, signed URLs, and configuration](docs/cloud-storage.md)
- [Notification consumption, deduplication, and validation](docs/notification-processing.md)
- [Local startup, emulators, and smoke check](docs/local-environment.md)
- [Test layers, recovery regressions, and commands](docs/testing.md)
- [Phase roadmap](docs/roadmap.md)
- [Infrastructure ownership](infrastructure/README.md)

Never commit service-account keys, real credentials, signed URLs, or Terraform state. Git ignore rules reduce accidents
but are not a secret scanner.

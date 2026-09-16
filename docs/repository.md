# Phase 2 — Repository and build

## Module boundaries

The root is both a Maven parent (inherited configuration) and aggregator (reactor membership). The group remains
`com.synechisveltiosi`; packages use `com.synechisveltiosi.platform.{order,document,notification}`. Each service scans
only its own package tree.

All three services depend on the two shared modules. No service depends on another service JAR. Shared contracts must
not depend on Spring, JPA, or service domain classes. Observability integration must be explicit when added, not broad
component scanning.

The five children inherit via `../../pom.xml`. Additional intermediate `services/pom.xml` and `shared/pom.xml`
aggregators would add hierarchy without different build policies.

## Maven choices

- Boot parent 4.1.1 manages Spring Framework 7 and build plugins. Do not override individual Spring versions casually.
- `maven.compiler.release=21` restricts bytecode and Java API usage even when a newer build JDK is used; CI/runtime will
  use JDK 21.
- JUnit Jupiter 5.14.4 overrides Boot's default 6.0.3 to meet the requested JUnit 5 baseline. Spring Framework 7's
  `SpringExtension` is incompatible with this JUnit 5 version: the migrated `@SpringBootTest` failed with
  `NoSuchMethodError` for `ExtensionContext.Store.computeIfAbsent`. The smoke test therefore starts and closes Spring
  programmatically. Future JUnit 5 integration tests must use explicit lifecycle management and real HTTP clients rather
  than `@SpringBootTest`/SpringExtension; switching to JUnit 6 would require an agreed requirement change.
- Parent `dependencyManagement` centralizes internal artifact versions without adding dependencies to every child. Child
  `dependencies` creates the actual dependency and reactor ordering.
- No Lombok: Java records and explicit constructors will express immutable data and injection without annotation
  processing.
- Only service POMs activate the Boot plugin. Shared modules remain ordinary dependency JARs; executable Boot archives
  have a different classpath layout.
- Surefire and Failsafe use Boot-managed versions. The inherited Failsafe execution runs integration-test and verify;
  avoid duplicating that execution.
- Enforcer requires Java 21+ and Maven 3.9.9 through 3.x. The wrapper pins 3.9.16. Use the pinned wrapper in CI.
- Database, GCP SDK/BOM, HTTP/security, Testcontainers, and telemetry dependencies will be introduced when used, with
  compatibility checks at that phase.

## Artifact and release model

Each service produces `target/<service>-0.0.1-SNAPSHOT.jar`. Shared versioning simplifies atomic source changes, but
deployment is independent: each service gets its own image and digest. A monorepo release version does not imply all
services deploy together.

Source synchronization does not replace event compatibility. Producers and consumers must tolerate old deployed
versions. Shared module changes require testing all dependents. Production image immutability and dependency/security
scanning arrive in Phase 16.

## Test scope

The existing generated startup smoke test was moved into order-service. It checks scaffold compatibility only; it does
not prove API, database, or messaging behavior. Meaningful failure tests arrive with their implementations and Phase 10.

## Key Points

1. Parent inheritance, reactor aggregation, and runtime deployment are separate concerns.
2. Shared modules have narrow responsibilities and cannot become a shared business model.
3. Buildable bootstrap services are not a working platform yet.

## Production Considerations

- One reactor version simplifies development but increases coordinated dependency changes.
- JUnit 5 is an intentional deviation from Boot's managed test baseline.
- Pinning versions is not a vulnerability update policy; updates and compatibility checks remain necessary.

## Interview Takeaway

A monorepo provides coordinated source changes, while service boundaries require independent data ownership, deployable
artifacts, and backward-compatible contracts.

## Official references

- [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules)
- [Maven dependency management](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism)
- [Boot executable archives](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)
- [JUnit BOM override with Spring Boot](https://docs.junit.org/5.14.1/running-tests/build-support.html)

## Validation at Phase 2

`./mvnw -B -ntp verify` passed using JDK 21.0.12.1 and wrapper Maven 3.9.16. All six reactor entries succeeded; the
single migrated startup test passed. Archive inspection confirmed three executable Boot JARs and two plain library JARs.
No cloud integration tests exist at this phase.

## Phase 3 update

The Phase 2 bare startup test has been replaced by PostgreSQL-backed startup and persistence integration tests. JUnit 5
still uses explicit Spring context lifecycle. Both database services now include JPA, Flyway, PostgreSQL, JSON support,
and Testcontainers dependencies. `verify` requires Docker; `test` runs domain checks without Docker.
See [Phase 3](domain-and-database.md).

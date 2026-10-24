# Insight Giver backend

Spring Boot 4.1 / Java 21 service for the Insight Giver diary app. README.md explains the
architecture; this file is the working agreement.

## Commands

```sh
./mvnw verify                                            # unit + Testcontainers integration tests (Docker required)
./mvnw test -Dtest=FuzzyRecallTest                       # one class
docker compose up -d && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # offline local run
```

## Layout

Package by feature under `com.insightgiver`:

- `account` — anonymous accounts, bearer-token filter, security config, devices (push tokens)
- `entry` — entries API, Redis queue, dispatcher, `@Async` embedding worker, pending-entry sweeper
- `embedding` — `EmbeddingClient` (Voyage, offline hashing stand-in)
- `insight` — hourly `@Scheduled` job, fuzzy recall, Claude/template writers, teaser policy, push payload
- `push` — `PushGateway` (FCM, logging)
- `support`, `config` — small shared helpers

## Conventions

- Persistence is plain SQL through `JdbcClient`; there is no JPA. Schema changes are new Flyway
  migrations (`V2__...sql`); never edit an applied one.
- Timestamps are `OffsetDateTime` in UTC from `Times.now(clock)`; inject `Clock` instead of calling
  `now()` so tests can move time.
- Each feature binds its own `@ConfigurationProperties` record; defaults live in `application.yml`.
- Providers (embedding, insight writer, push) are chosen by a property in a `*Config` class. The
  default profile uses the real ones and fails fast without credentials; `local` and `test` use
  offline stand-ins.
- `EmbeddingClient.DIMENSIONS` must match `vector(1024)` in the schema.
- `InsightPayload` keys are read by native code in `insight-giver-mobile`
  (`modules/insight-native`). Change both repositories together, and keep payloads well under 4 KB.
- Claude calls use the official `anthropic-java` SDK. Keep the model, adaptive thinking, explicit
  effort and the server-side refusal fallback (`fallbacksDefault()` with
  `SERVER_SIDE_FALLBACK_2026_07_01`) unless there is a reason to change them.
- Integration tests use the `@IntegrationTest` annotation so they all share one Spring context and
  one pair of containers; give each test its own account instead of cleaning tables.

# Insight Giver — backend

The server side of Insight Giver, an asynchronous diary. The app drops unstructured thoughts here
and expects no reply. In the background every entry is embedded, and once a day the service recalls
an older memory related to something recent, has Claude word the connection as a short note, and
sends it to the app's home-screen widget as a silent push.

Java 21 · Spring Boot 4.1 · PostgreSQL + pgvector · Redis · FCM (Android) · APNs (iOS) · Claude

## How it works

```
app ── POST /api/v1/entries ──▶ Postgres (entry, PENDING) ──▶ Redis list (entry id)
                                                                   │
                         EntryQueueDispatcher (blocking pop) ◀─────┘
                                   │
                                   ▼
                @Async EntryEmbeddingWorker ── EmbeddingClient ──▶ entries.embedding (vector(1024))

@Scheduled InsightScheduler (hourly) ── accounts whose local delivery hour has passed today
      │
      ├─ FuzzyRecall: recent entry ─▶ blurred vector ─▶ pgvector nearest older entries ─▶ sampled memory
      ├─ InsightWriter (Claude) ─▶ short note in the person's language
      ├─ TeaserPolicy ─▶ sometimes a truncated preview for the widget
      └─ PushGateway ─▶ FCM (Android) / APNs (iOS) silent push, handled natively by the app
```

- **Ingestion.** `POST /api/v1/entries` stores the entry as `PENDING` and pushes its id onto a Redis
  list, then returns `202`. Postgres stays the source of truth: if Redis loses an id, the
  `PendingEntrySweeper` re-queues the entry once its `retry_at` passes. Failed embeddings back off
  exponentially and are marked `FAILED` after `max-attempts`. Resending an entry with the same
  `clientId` never creates a duplicate, so the app can retry freely.
- **Workers.** One dispatcher thread blocks on the Redis list and hands each id to
  `EntryEmbeddingWorker.embed`, an `@Async` method on a bounded pool. When every worker is busy,
  the caller-runs policy makes the dispatcher do the work itself, so a backlog waits in Redis.
- **Fuzzy memory model.** Recall is cued by one of the newest entries, but imprecisely, like human
  memory: the cue vector gets Gaussian noise (`blur`), the nearest older entries are fetched with
  pgvector's cosine distance, and one of them is sampled with a softmax over similarity
  (`temperature`). The same diary yields different but related connections from day to day.
  Searches are exact and scoped to one account; an ANN index would filter by account only after the
  approximate search and silently drop results (see `V1__init.sql`).
- **Delivery.** The job runs hourly and serves every account whose local `delivery-hour` has passed
  today without an insight, so each person gets theirs in their own morning, and a failed attempt is
  retried by the next run. An atomic claim on `accounts.last_insight_at` keeps parallel runs and
  multiple instances from double-sending.
- **Anti-throttling teasers.** iOS and Android ration background pushes by how much the app is
  used. When someone hasn't opened the app for `teaser.away-after` (and at random otherwise), the
  push carries a truncated `preview`; the widget shows only that until the app is opened.

## API

All endpoints except account creation need `Authorization: Bearer <token>`.

| Method | Path                      | Purpose                                                                  |
| ------ | ------------------------- | ------------------------------------------------------------------------ |
| POST   | `/api/v1/accounts`        | `{timeZone}` → `{accountId, token}`. Anonymous, device-bound account.    |
| PUT    | `/api/v1/me/device`       | `{platform, pushToken, apnsEnvironment, timeZone}`. Sent on every launch. |
| POST   | `/api/v1/entries`         | `{clientId, content, writtenAt}` → `202 {id, clientId, status}`.         |
| GET    | `/api/v1/insights/latest` | `{id, body, createdAt}`, or `204` before the first insight.              |
| GET    | `/actuator/health`        | Liveness and readiness.                                                  |

Only the SHA-256 of each token is stored. Errors are RFC 9457 problem details. `pushToken` is the
FCM registration token on Android and the hex APNs device token on iOS, where `apnsEnvironment`
(`SANDBOX` for development-signed builds, otherwise `PRODUCTION`) picks the APNs host.

### Push payload

Android gets an FCM data-only message. iOS gets an APNs background push (`content-available`, push
type `background`, priority 5) sent straight to Apple over HTTP/2 with token-based (.p8)
authentication; the keys below sit next to `aps` at the top level. The app's native handlers read
them directly, so they are a contract with `insight-giver-mobile/modules/insight-native`; change
both sides together.

| Key         | Value                                                         |
| ----------- | ------------------------------------------------------------- |
| `type`      | `insight`                                                     |
| `insightId` | UUID                                                          |
| `body`      | the full insight (at most `max-length` chars)                 |
| `preview`   | present only when the widget should show a truncated teaser   |
| `createdAt` | ISO-8601 instant                                              |

## Running locally

Needs Java 21 and Docker.

```sh
docker compose up -d                                   # Postgres with pgvector, Redis
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile runs offline: deterministic hashing embeddings, template-worded insights and
pushes written to the log. Swap in real providers one at a time with the environment variables
below. Try it:

```sh
TOKEN=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"timeZone":"Asia/Seoul"}' | jq -r .token)
curl -s -X POST localhost:8080/api/v1/entries -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"1","content":"Ran by the river, head felt clear","writtenAt":"2026-10-01T08:00:00Z"}'
```

To watch an insight being made without waiting for the hourly run, start the app with
`INSIGHT_GIVER_INSIGHTS_CRON='*/30 * * * * *'` in the environment.

## Configuration

Defaults are in `src/main/resources/application.yml`. Without a profile the app expects the real
providers and fails at startup if a key is missing.

| Variable                    | Default                                        | Notes                                                   |
| --------------------------- | ---------------------------------------------- | ------------------------------------------------------- |
| `DATABASE_URL`              | `jdbc:postgresql://localhost:5432/insight_giver` | also `DATABASE_USERNAME`, `DATABASE_PASSWORD`          |
| `REDIS_URL`                 | `redis://localhost:6379`                       |                                                         |
| `EMBEDDING_PROVIDER`        | `voyage`                                       | `voyage` or `hashing` (offline stand-in, not for production) |
| `VOYAGE_API_KEY`            |                                                | Voyage AI, model `voyage-4-lite` at 1024 dimensions     |
| `INSIGHT_WRITER`            | `claude`                                       | `claude` or `template`                                  |
| `ANTHROPIC_API_KEY`         |                                                | if unset, the SDK's other credential sources are tried  |
| `ANDROID_PUSH_PROVIDER`     | `fcm`                                          | `fcm` or `log`                                          |
| `FIREBASE_CREDENTIALS_FILE` |                                                | service-account JSON; Application Default Credentials when unset |
| `IOS_PUSH_PROVIDER`         | `apns`                                         | `apns` or `log`                                         |
| `APNS_TEAM_ID`, `APNS_KEY_ID` |                                              | from the Apple Developer portal (Keys)                  |
| `APNS_PRIVATE_KEY`          |                                                | the .p8 key's contents, or `APNS_PRIVATE_KEY_FILE` with its path |
| `APNS_TOPIC`                | `com.insightgiver.app`                         | the app's bundle id                                     |

Insights are written by `claude-opus-5-5` with adaptive thinking at `medium` effort
(`insight-giver.claude.*`). Requests opt into Anthropic's server-side refusal fallback
(`fallbacks: "default"`), so a request a safety classifier declines is retried on the
recommended fallback model instead of failing.

Entries embedded by one model are never compared with another model's vectors. After switching
models, queue the old entries for re-embedding:

```sql
UPDATE entries SET status = 'PENDING', attempts = 0, retry_at = now() WHERE embedding_model <> 'voyage/voyage-4-lite';
```

## Tests

```sh
./mvnw verify
```

Integration tests start Postgres (pgvector) and Redis with Testcontainers, so Docker must be
running. They drive the real pipeline: HTTP → Redis → `@Async` embedding → recall → push.

## Deploying

`./mvnw spring-boot:build-image` builds an OCI image. Run Redis with AOF persistence (as in
`compose.yaml`). Several instances can share the database and Redis: queue pops, sweeper claims
(`FOR UPDATE SKIP LOCKED`) and insight slots are all safe to run concurrently.

Diary entries go to the embedding provider and, for recalled pairs, to the Claude API.

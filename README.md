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

@Scheduled InsightScheduler (every 5 min) ── accounts whose delivery time has passed today
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
- **Delivery.** Each account picks when in its own day the insight comes (`PUT /api/v1/me/delivery`):
  - `MORNING` (default): at `delivery.morning`, 09:00.
  - `FIXED`: at a time the person picked, on a quarter hour.
  - `SURPRISE`: at a different time every day between `delivery.surprise-from` and `surprise-to`
    (09:00–21:00). `DeliverySchedule` derives it from the account id and the date, so every run and
    instance agrees on today's time without storing it.

  The job runs every five minutes and serves each account whose time has passed today, in its own
  time zone, with no insight yet that local day and none in the last 12 hours. A failed attempt
  waits `delivery.retry-after` (30 min) before the next try. An atomic claim on
  `accounts.last_insight_at` keeps parallel runs and multiple instances from double-sending.
- **Anti-throttling teasers.** iOS and Android ration background pushes by how much the app is
  used. When someone hasn't opened the app for `teaser.away-after` (and at random otherwise), the
  push carries a truncated `preview`; the widget shows only that until the app is opened.

## API

All endpoints except account creation need `Authorization: Bearer <token>`.

| Method | Path                      | Purpose                                                                  |
| ------ | ------------------------- | ------------------------------------------------------------------------ |
| POST   | `/api/v1/accounts`        | `{timeZone}` → `{accountId, token}`. Anonymous, device-bound account.    |
| PUT    | `/api/v1/me/device`       | `{platform, pushToken, apnsEnvironment, timeZone}`. Sent on every launch. |
| GET    | `/api/v1/me/delivery`     | `{mode, time}`: when the daily insight comes.                            |
| PUT    | `/api/v1/me/delivery`     | `{mode, time}`, `mode` one of `MORNING`, `FIXED`, `SURPRISE`; `time` is `HH:mm` on a quarter hour. Without `time` the earlier one is kept, so a switch back to `FIXED` needs none. |
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

An insight comes once the account's delivery time has passed; with the default `MORNING` mode, test
after 09:00 in the account's time zone, or switch it to a time that has already passed today:

```sh
curl -s -X PUT localhost:8080/api/v1/me/delivery -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"mode":"FIXED","time":"00:00"}'
```

## Configuration

Defaults are in `src/main/resources/application.yml`. Without a profile the app expects the real
providers and fails at startup if a key is missing.

| Variable                    | Default                                        | Notes                                                   |
| --------------------------- | ---------------------------------------------- | ------------------------------------------------------- |
| `DATABASE_URL`              | `jdbc:postgresql://localhost:5432/insight_giver` | a JDBC URL, or its parts: `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`; also `DATABASE_USERNAME`, `DATABASE_PASSWORD` |
| `REDIS_URL`                 | `redis://localhost:6379`                       |                                                         |
| `PORT`                      | `8080`                                         | HTTP port                                               |
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

The `Dockerfile` builds a self-contained image (`docker build .`; CI builds it on every push).
The service has to stay up: the insight scheduler and the queue dispatcher run inside it, so a
platform that scales to zero when idle would miss deliveries. Several instances can share the
database and Redis (queue pops, sweeper claims with `FOR UPDATE SKIP LOCKED` and insight slots are
all safe to run concurrently), but one is enough for a long while. Redis needs no persistence:
Postgres is the source of truth, and the sweeper re-queues whatever Redis loses.

`render.yaml` is a [Render](https://render.com) Blueprint for the whole stack, in one region on
Render's private network:

- the API as a Docker web service (starter, 512 MB);
- Postgres 17 (basic-256mb). pgvector is available there, and `V1__init.sql` enables it;
- a free Key Value instance for the queue.

That is roughly US$13 a month. To set it up:

1. In the dashboard, choose New > Blueprint and pick this repository.
2. Enter the secrets it asks for: `VOYAGE_API_KEY`, `ANTHROPIC_API_KEY`, `APNS_TEAM_ID`,
   `APNS_KEY_ID` and `APNS_PRIVATE_KEY`.
3. Upload the Firebase service-account JSON as the secret file `firebase-service-account.json`.
4. Point the app's `EXPO_PUBLIC_API_URL` at the service's `onrender.com` URL.

Diary entries go to the embedding provider and, for recalled pairs, to the Claude API.

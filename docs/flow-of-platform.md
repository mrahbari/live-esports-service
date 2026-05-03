# Platform Flow — End-to-End Technical Reference

> Audience: technical meeting / engineering review  
> Covers: both execution paths (snapshot track + polling track), every layer, and all infrastructure decisions.

---

## 1. What the System Does

The service is a **live esports data hub** built on top of the Abios Atlas API.  
It solves one core problem: the Abios API is paginated, rate-limited, and requires multiple round-trips to assemble useful data. Clients should never feel that pain.

The system exposes two independent tracks:

| Track | Source of truth | Freshness | Primary use |
|---|---|---|---|
| **Snapshot track** | In-memory or Redis cache | 15–20 s TTL, background refresh | Fast read (sub-ms), enriched (teams + players) |
| **Polling track** | PostgreSQL `live_series` table | 2–5 s for live, 10–25 s for upcoming | Persistent, lifecycle-aware, cursor-paginated |

Both tracks are served from the same running process. They do not interfere with each other.

---

## 2. Infrastructure at a Glance

```
┌─────────────────────────────────────────────────────────────────┐
│                        docker compose                           │
│                                                                 │
│  ┌──────────────────────────────────┐   ┌──────────────────┐   │
│  │     Spring Boot app :8080        │   │  Redis :6379     │   │
│  │                                  │   │  (optional cache │   │
│  │  ┌────────────┐ ┌─────────────┐  │   │   + rate-limit   │   │
│  │  │ Snapshot   │ │  Polling    │  │   │   bucket store)  │   │
│  │  │ Track      │ │  Track      │  │   └──────────────────┘   │
│  │  └────────────┘ └─────────────┘  │                          │
│  └──────────────────────────────────┘   ┌──────────────────┐   │
│                                         │ PostgreSQL :5432  │
│                                         │  live_series      │
│                                         │  live_teams       │
│                                         │  live_players     │
│                                         └──────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
          ↕ outbound HTTP (rate-limited, retried, circuit-broken)
   Abios Atlas API  (api.abiosgaming.com  /  atlas.abiosgaming.com)
```

**Startup sequence:**
1. `postgres` container starts → Flyway runs V1 + V2 migrations
2. `redis` container starts
3. `app` container starts only after both are healthy (`depends_on: condition: service_healthy`)
4. Spring Boot context loads → config validated → `LiveDataService` triggers an eager cold-start fetch
5. Both polling workers start their first cycle within milliseconds of context ready

---

## 3. Configuration Layers

All tunables are environment variables. The app reads them through two `@ConfigurationProperties` beans.

### `AbiosProperties` (`abios.*`)

| Property | Default | Meaning |
|---|---|---|
| `mock-enabled` | `true` | Read from classpath JSON instead of hitting the API |
| `base-url` | `https://api.abiosgaming.com` | Upstream base |
| `series-path` | `/v3/series` | Path for series fetch |
| `batch-size` | `50` | Items per paginated request |
| `max-series-pages` | `25` | Hard cap on pages per ingestion cycle |
| `live-polling-query` | `filter=lifecycle<=live` | Query string used by `LiveSeriesWorker` |
| `upcoming-polling-query` | `filter=lifecycle<=upcoming&ordering=start-asc` | Query string used by `UpcomingSeriesWorker` |
| `connect-timeout-ms` | `3000` | TCP connect timeout |
| `read-timeout-ms` | `8000` | Response read timeout |

### `LiveDataProperties` (`app.live.*`)

| Property | Default | Meaning |
|---|---|---|
| `cache-mode` | `memory` | `memory` or `redis` |
| `cache-ttl-seconds` | `20` | Time before background refresh is triggered |
| `background-refresh-ms` | `15000` | Scheduler interval for the refresh job |
| `max-stale-ttl-seconds` | `300` | How long stale data is still served before hard-expiry |
| `client-rate-limit-per-minute` | `120` | Per-IP inbound token bucket refill rate |
| `client-burst-capacity` | `120` | Per-IP burst allowance |

### `PollingProperties` (`app.polling.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Set `false` in tests to disable workers |
| `live-interval-ms` | `3000` | Delay between LiveSeriesWorker cycles |
| `upcoming-interval-ms` | `15000` | Delay between UpcomingSeriesWorker cycles |
| `max-upcoming-pages` | `5` | Page cap for upcoming fetch (avoids fetching entire future schedule) |
| `ended-threshold-cycles` | `3` | Series not seen for N × interval → marked `ended` |
| `max-concurrent-calls` | `4` | Shared semaphore across all polling workers |

### Spring profiles

| Profile | Activation | Effect |
|---|---|---|
| `default` | `./scripts/run.sh` | Mock data, memory cache |
| `atlas` | `./scripts/run-atlas.sh` | Real API, extended TTLs, stricter rate limits |

---

## 4. Database Schema

Flyway applies versioned migrations at startup. As of V3, the schema is consolidated into three main tables.

### Consolidated Tables

```sql
live_series (
  id               VARCHAR      PRIMARY KEY,
  name             VARCHAR,
  game_name        VARCHAR,
  state            VARCHAR(50)  NOT NULL,       -- live | upcoming | ended
  started_at       TIMESTAMPTZ,
  tier             INTEGER,
  best_of          INTEGER,
  team_count       INTEGER,
  resource_version INTEGER,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
)

live_teams (
  id           VARCHAR      PRIMARY KEY,
  name         VARCHAR,
  abbreviation VARCHAR,
  player_count INTEGER,
  series_ids   TEXT[]       NOT NULL DEFAULT '{}', -- array of live_series.id
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
)

live_players (
  id           VARCHAR      PRIMARY KEY,
  nickname     VARCHAR,
  first_name   VARCHAR,
  last_name    VARCHAR,
  role         VARCHAR,
  team_id      VARCHAR,
  team_name    VARCHAR,
  series_ids   TEXT[]       NOT NULL DEFAULT '{}', -- array of live_series.id
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
)
```

**Indexes:**
```sql
idx_live_series_state_id   ON live_series (state, id)       -- cursor pagination per state
idx_live_series_updated_at ON live_series (updated_at)      -- cleanup and ended-detection
idx_live_teams_series_ids   ON live_teams   USING GIN (series_ids);
idx_live_players_series_ids ON live_players USING GIN (series_ids);
```

**Data persistence:** The tables live in the `postgres_data` named Docker volume.  
`docker compose down` keeps the data. Only `docker compose down -v` removes it.

---

## 5. The Snapshot Track (V1 endpoints)

### Purpose
Serve fully enriched, deduplicated snapshots of live series + their teams + players with sub-millisecond response time. The enrichment pipeline runs in the background; clients never wait for it.

### Background refresh flow

```
LiveDataRefreshJob (@Scheduled, every 15 s)
  └─► LiveDataService.refreshInBackground()
        └─► LiveSnapshotAggregator.aggregate()
              ├─ Phase 1: fetchSeries (paginated, take=50)
              │           AbiosSnapshotReader → ResilientAbiosFetch → AbiosDataGateway
              ├─ Phase 2: extract unique rosterIds, teamIds, playerIds
              ├─ Phase 3: fetchRosters (batch)  → discover more teamIds + playerIds
              ├─ Phase 4+5 (parallel, virtual threads):
              │     fetchTeams (batch)
              │     fetchLineups (batch)
              └─ Phase 6: fetchPlayers (batch)  → full player metadata
        └─► builds LiveSnapshot { series[], teams[], players[], fetchedAt, atlasCalls }
        └─► LiveSnapshotCache.put(snapshot)
```

The cache implementation is selected at startup:
- `CACHE_MODE=memory` → `InMemoryLiveSnapshotCache` (AtomicReference, zero I/O)
- `CACHE_MODE=redis` → `RedisLiveSnapshotCache` (serialized JSON, L1 in-process TTL of 2 s)

### Request path (snapshot track)

```
Client GET /v1/series/live
  └─► LiveDataController
        └─► LiveDataService.getSnapshot()
              ├─ snapshot present + fresh?  → return as-is  (sub-ms)
              ├─ snapshot present + stale?  → trigger async refresh, return stale  (sub-ms)
              └─ snapshot absent / hard-expired? → block until fresh (first request only)
        └─► slice items by ?skip / ?take
        └─► FieldSelectionAdvice intercepts response
              └─► if ?fields= present → Jackson filter strips non-requested fields
        └─► ObservabilityFilter logs latency, RequestIdFilter adds X-Request-Id
```

### Response envelope

```json
{
  "meta": {
    "fetchedAt": "2026-05-03T10:00:00Z",
    "stale": false,
    "degraded": false,
    "count": 10,
    "total": 42,
    "skip": 0,
    "take": 10,
    "hasMore": true,
    "atlasCalls": { "seriesPages": 2, "rosterBatches": 1, "teamBatches": 1, "playerBatches": 2 }
  },
  "items": [ ... ]
}
```

`degraded: true` means the snapshot is older than `max-stale-ttl-seconds` — the upstream has been unreachable for a while. Players are dropped from the payload in degraded mode to reduce bandwidth.

---

## 6. The Polling Track (V2 endpoints)

### Purpose
Maintain a persisted, lifecycle-aware record of active series updated every few seconds. No enrichment, no teams/players — just series identity and lifecycle state. Built for systems that need a durable, queryable record of what is live or upcoming right now.

### Two independent workers

Both workers are `@Scheduled` Spring components, running on a shared `ThreadPoolTaskScheduler` (4 threads) so they execute in parallel without blocking each other.

#### LiveSeriesWorker — fires every 3 seconds

```
LiveSeriesWorker.poll()
  ├─ mock mode?
  │     └─► gateway.readMock().allSeries()
  │           filter lifecycle in {live, ongoing}
  │           upsert each as state='live'
  │
  └─ production mode?
        skip=0, take=50
        loop:
          semaphore.acquire()           ← shared, max 4 concurrent API calls
          resilient.fetchSeriesWithQuery(take, skip, "filter=lifecycle<=live")
            └─► @Retry + @RateLimiter + @CircuitBreaker (Resilience4j)
                └─► AbiosDataGateway.doFetchSeries()
          semaphore.release()
          upsert each series as state='live'
          if page < 50 → break          ← last page
          skip += 50
        markEndedIfStale("live", 3 × 3s = 9s)
          UPDATE live_series SET state='ended', updated_at = NOW()
          WHERE state='live' AND updated_at < now() - 9s
```

#### UpcomingSeriesWorker — fires every 15 seconds

Same structure, but:
- Query: `filter=lifecycle<=upcoming&ordering=start-asc`
- Page cap: `maxUpcomingPages = 5` (first 250 upcoming series only)
- Ended threshold: `3 × 15s = 45s`
- Upserts with `state='upcoming'`

#### Lifecycle transitions

| Event | How it happens |
|---|---|
| Series goes live | LiveSeriesWorker upserts the row with `state='live'`, overwriting `upcoming` |
| Series ends | No explicit API signal — if not seen for N cycles, worker marks `state='ended'` |
| Series reappears | Upsert resets `updated_at` and `state`, reactivating it |

#### Upsert logic (PostgreSQL ON CONFLICT)

```sql
INSERT INTO live_series (id, state, started_at, resource_version, updated_at)
VALUES (:id, :state, :startedAt, :resourceVersion, :now)
ON CONFLICT (id) DO UPDATE SET
    state            = EXCLUDED.state,
    started_at       = EXCLUDED.started_at,
    resource_version = COALESCE(EXCLUDED.resource_version, live_series.resource_version),
    updated_at       = EXCLUDED.updated_at
```

Every poll cycle touches every visible series — `updated_at` is always current for active series.

### Request path (polling track)

```
Client GET /v2/series/live?take=50&cursor=1310073261
  └─► SeriesPollingController
        └─► PollingSeriesRepository.getByState("live", cursor=1310073261, limit=50)
              SELECT id, state, started_at, updated_at
              FROM live_series
              WHERE state = 'live' AND id > '1310073261'
              ORDER BY id ASC
              LIMIT 51                ← fetch limit+1 to detect hasMore
        └─► return CursorPage { items, nextCursor, count, hasMore }
```

### Response shape (polling track)

```json
{
  "items": [
    {
      "id": "1310073261",
      "state": "live",
      "startedAt": "2025-04-20T16:00:00Z",
      "updatedAt": "2026-05-03T10:00:01Z"
    }
  ],
  "nextCursor": "1310073261",
  "count": 1,
  "hasMore": false
}
```

Pagination: pass `nextCursor` as the `cursor` param on the next request.  
There is no offset — the cursor is the last `id` seen, so it is stable even if new rows are inserted mid-page.

---

## 7. Resilience Layers

Every outbound call to Abios passes through three Resilience4j decorators in `ResilientAbiosFetch`, applied in this order (outer to inner): CircuitBreaker → RateLimiter → Retry.

### Rate limiter (`abiosOut`)
```yaml
limit-for-period: 30
limit-refresh-period: 1m
timeout-duration: 0s   # fail fast rather than queue
```
Caps outbound calls to 30/min. Combined with the polling semaphore (4 concurrent), this prevents burst saturation of the upstream.

### Retry (`abios`)
```yaml
max-attempts: 3
wait-duration: 200ms
enable-exponential-backoff: true
exponential-backoff-multiplier: 2
randomized-wait-factor: 0.5
retry-exceptions:
  - AbiosUpstreamTransientException   # 5xx
  - AbiosUpstreamRateLimitedException # 429
```
429 responses carry a `Retry-After` header. The gateway throws `AbiosUpstreamRateLimitedException` with the parsed seconds value, and Resilience4j retries after the prescribed delay.

### Circuit breaker (`abios`)
```yaml
sliding-window-size: 20
failure-rate-threshold: 50%
wait-duration-in-open-state: 30s
```
If more than half of the last 20 calls fail, the breaker opens and all calls fail immediately for 30 seconds (half-open probe after). The snapshot track falls back to stale data during an open breaker. The polling track logs a warning and skips the cycle.

### Polling semaphore
A `java.util.concurrent.Semaphore` with `maxConcurrentCalls=4` permits is shared between `LiveSeriesWorker` and `UpcomingSeriesWorker`. Each page fetch acquires one permit before calling the API and releases it in a `finally` block. This caps concurrent in-flight API calls regardless of how many workers run.

### Inbound rate limiter (per client IP)
Bucket4j token bucket, one bucket per IP stored in Caffeine (memory) or Redis (distributed).  
Default: 120 requests/minute, burst 120. Returns HTTP 429 + `Retry-After` when exhausted.

---

## 8. All Endpoints

### Snapshot track (V1)

| Method | Path | Description |
|---|---|---|
| GET | `/v1/series/live` | Live series from snapshot cache |
| GET | `/v1/players/live` | Players in live series (empty when degraded) |
| GET | `/v1/teams/live` | Teams in live series |

Aliases: `/series/live`, `/players/live`, `/teams/live`, `/v1/series`, `/v1/players`, `/v1/teams`

Query params: `?skip=` `?take=` `?forceRefresh=true` `?fields=f1,f2`

### Polling track (V2)

| Method | Path | Description |
|---|---|---|
| GET | `/v2/series/live` | Live series from `series` table, cursor-paginated |
| GET | `/v2/series/upcoming` | Upcoming series from `series` table, cursor-paginated |

Query params: `?cursor=<last_id>` `?take=<1-200>`

### Infrastructure

| Method | Path | Description |
|---|---|---|
| GET | `/actuator/health` | Overall health |
| GET | `/actuator/health/liveness` | Kubernetes liveness probe |
| GET | `/actuator/health/readiness` | Kubernetes readiness probe |
| GET | `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| GET | `/swagger-ui` | Interactive API docs |
| GET | `/v3/api-docs` | Raw OpenAPI 3 JSON |

---

## 9. Running the System

### Start (mock mode — no API key)
```bash
./scripts/run.sh
```
Builds the Docker image, starts postgres + redis + app. Flyway migrates on first boot.  
Polling workers begin within seconds. Data appears in `/v2/series/live` and `/v2/series/upcoming` immediately.

### Start (real Atlas API)
```bash
export ABIOS_API_KEY=your_key_here
./scripts/run-atlas.sh
```

### Verify everything works
```bash
# Snapshot track
curl http://localhost:8080/v1/series/live

# Polling track
curl http://localhost:8080/v2/series/live
curl http://localhost:8080/v2/series/upcoming

# Health
curl http://localhost:8080/actuator/health

# Smoke suite
./scripts/run-smoke-tests.sh
```

### Stop
```bash
./scripts/stop.sh          # data survives (named volume)
docker compose down -v     # data erased
```

### Inspect the database directly
```bash
docker compose exec postgres psql -U esports -d esports \
  -c "SELECT id, state, started_at, updated_at FROM live_series ORDER BY state, id;"
```

---

## 10. Code Map — Where Is What

```
src/main/java/com/abioscase/live/
│
├── config/
│   ├── AbiosProperties.java          upstream API config (URL, auth, batch sizes, polling queries)
│   ├── LiveDataProperties.java       snapshot track config (TTLs, cache mode, rate limits)
│   ├── PollingProperties.java        polling track config (intervals, page cap, semaphore size)
│   ├── AppConfiguration.java         RestClient, Redis ProxyManager, enable @ConfigurationProperties
│   └── TaskConfiguration.java        ThreadPoolTaskScheduler, cacheRefreshExecutor, pollingApiSemaphore
│
├── integration/abios/
│   ├── AbiosDataGateway.java         raw HTTP calls + mock reader (no Resilience4j here)
│   ├── ResilientAbiosFetch.java      @Retry + @RateLimiter + @CircuitBreaker wrappers
│   ├── AbiosSnapshotReader.java      6-phase parallel enrichment pipeline
│   ├── AbiosMockReader.java          classpath JSON loader for mock mode
│   ├── AbiosResponseParser.java      Jackson deserialization + logging
│   ├── model/                        AbiosSeriesNode, AbiosTeamNode, AbiosPlayerNode, AbiosRosterNode
│   └── exception/                    AbiosUpstreamException hierarchy (transient, rate-limited, auth, etc.)
│
├── livedata/
│   ├── application/
│   │   ├── LiveDataService.java      snapshot lifecycle (stale-while-revalidate, lock, degraded mode)
│   │   └── LiveSnapshotAggregator.java  orchestrates enrichment, builds LiveSnapshot
│   ├── cache/
│   │   ├── LiveSnapshotCache.java    interface
│   │   ├── InMemoryLiveSnapshotCache.java   AtomicReference
│   │   └── RedisLiveSnapshotCache.java      JSON + L1 Caffeine TTL
│   ├── db/
│   │   ├── ingestion/StreamingIngestionPipeline.java  writes live_series / live_teams / live_players
│   │   └── service/DbLiveDataService.java             reads those tables (cursor-paginated)
│   ├── polling/
│   │   ├── LiveSeriesWorker.java     @Scheduled live poller → live_series table
│   │   ├── UpcomingSeriesWorker.java @Scheduled upcoming poller → live_series table
│   │   └── PollingSeriesRepository.java  upsert, markEnded, cursor query on live_series table
│   ├── scheduler/
│   │   └── LiveDataRefreshJob.java   @Scheduled → LiveDataService.refreshInBackground()
│   └── web/
│       ├── rest/LiveDataController.java          V1 snapshot endpoints
│       ├── rest/SeriesPollingController.java      V2 polling endpoints
│       ├── dto/                                   LiveSeriesItem, LiveTeamItem, LivePlayerItem, SeriesRow
│       └── filter/                                ObservabilityFilter, RequestIdFilter, IpRateLimitFilter, FieldSelectionAdvice
│
└── shared/
    ├── exception/ApiExceptionHandler.java
    └── util/HttpUtils.java
```

---

## 11. Key Design Decisions

| Decision | Rationale |
|---|---|
| Two independent tracks (snapshot + polling) | Snapshot serves enriched, consistent data fast. Polling serves raw, persistent series state with low lag. Different use cases, different contracts. |
| Snapshot uses in-memory AtomicReference | Single object swap is lock-free, zero serialization cost. O(1) reads regardless of dataset size. |
| Polling uses PostgreSQL, not Redis | Data must outlive the app process. Named Docker volume ensures persistence across restarts. Redis is cache — postgres is state. |
| Consolidated schema (V3) | Merged 'series' into 'live_series' and replaced junction tables with GIN-indexed arrays. Reduces JOIN overhead and simplifies data management. |
| Cursor pagination (id > :cursor) instead of offset | Offset pagination breaks when new rows are inserted mid-page. Cursor pagination is stable and O(log n) via the B-tree index. |
| Shared semaphore across workers | Both workers compete for the same upstream capacity. Resilience4j rate limiter controls time-window throughput; the semaphore controls instantaneous concurrency. |
| `markEndedIfStale` instead of DELETE | Ended series remain in the DB as historical record. state='ended' tells consumers the series is over without losing the row. |
| `@ConditionalOnProperty(app.polling.enabled)` | Tests set `enabled: false` to prevent workers from touching H2 tables that Flyway didn't create (Flyway is also disabled in tests). |
| Single `ThreadPoolTaskScheduler` bean named `taskScheduler` | Spring Boot uses the bean named `taskScheduler` for all `@Scheduled` tasks. Pool size 4 ensures LiveSeriesWorker and UpcomingSeriesWorker run truly in parallel. |
| `baseline-on-migrate: true` in Flyway config | Allows Flyway to run on a database that already has tables from a manual setup, without failing on the baseline version check. |

---

## 12. Observability

| Signal | Where |
|---|---|
| Structured logs (ECS format) | stdout → `docker compose logs -f app` |
| Request latency + status | `ObservabilityFilter` logs every request with duration |
| Upstream error counters | `meterRegistry.counter("upstream.error.status", "code", ...)` in `AbiosDataGateway` |
| Circuit breaker state | `/actuator/health` → `circuitBreakers` component |
| Rate limiter state | `/actuator/health` → `rateLimiters` component |
| All Micrometer metrics | `/actuator/prometheus` — scrape with Prometheus, visualize in Grafana |
| Polling worker activity | `INFO` log per cycle only when `upserted > 0 || ended > 0` (silent on quiet cycles) |

---

## 13. What Happens on Failure

| Failure | Snapshot track | Polling track |
|---|---|---|
| Abios API returns 429 | Resilience4j retries after `Retry-After` seconds (up to 3 attempts) | Same — semaphore holds next page until retry succeeds |
| Abios API returns 5xx | Retry with exponential backoff (200ms, 400ms, 800ms) | Same |
| Circuit breaker opens | `LiveDataService` serves last known snapshot (stale) until breaker recovers | Workers log a warning, skip the cycle, try again next interval |
| Snapshot older than `max-stale-ttl-seconds` | Response includes `"degraded": true`; players payload emptied | No effect (independent) |
| PostgreSQL unreachable | No effect on snapshot track | Workers fail the cycle, log warning, retry on next interval |
| Redis unreachable (CACHE_MODE=redis) | Falls back to blocking refresh on every request | No effect (independent) |
| App restart | Snapshot cache is empty → cold-start fetch on first request | Workers resume immediately; `series` table still has all previous data |

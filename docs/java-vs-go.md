# Java vs Go — Engineering Evaluation

> Context: live esports data service. Polls Abios Atlas, enriches data with parallel batch calls,
> caches the result, and serves three REST endpoints under a per-IP rate limiter.

---

## 1. High-level Verdict

| Dimension | Java (this implementation) | Go (idiomatic equivalent) |
|---|---|---|
| Startup time | ~3–6 s (JVM + Spring) | ~50–200 ms |
| Memory baseline | ~250–400 MB | ~20–50 MB |
| Concurrency model | Virtual threads + CompletableFuture | Goroutines + channels (native) |
| Resilience primitives | Resilience4j (library, annotations) | Hand-rolled or `sony/gobreaker` |
| Type safety | Strong, verbose | Strong, concise |
| Ecosystem for this task | Excellent (Spring, Caffeine, Bucket4j) | Good but more assembly required |
| Deployment artifact | Fat JAR (~30 MB) | Single static binary (~10 MB) |
| Cold-start suitability | Poor | Excellent |

---

## 2. Strengths of the Java Implementation

- **Resilience4j annotations** (`@Retry`, `@RateLimiter`, `@CircuitBreaker`) make the resilience policy
  readable and completely separated from business logic (`ResilientAbiosFetch`).
- **Spring Boot auto-configuration** eliminates boilerplate for HTTP server, health probes,
  metrics (Micrometer → Prometheus), structured logging (ECS), and Redis.
- **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()` in `AbiosSnapshotReader`) give
  cheap I/O concurrency without rewriting to reactive — a Java 21 win that narrows the gap with Go.
- **Jackson** handles the messy Abios schema quirks (custom deserializers, `@JsonAlias`, nullable
  fields) with minimal friction.
- **Interface-backed cache** (`LiveSnapshotCache`) makes the memory/Redis swap a single config flag —
  clean strategy pattern.

---

## 3. Weaknesses of Java for This Use Case

- **Resource overhead** — a service that spends most of its time waiting on 5–8 upstream HTTP calls
  does not need a 350 MB JVM heap. Go runtime costs a fraction.
- **Startup latency** — 3–6 s makes this unsuitable for serverless or autoscaled environments where
  instances need to spin up fast.
- **Annotation-driven magic** — `@CircuitBreaker`, `@Scheduled`, `@ConditionalOnProperty` are
  invisible to the reader. Go forces the same logic to be explicit and traceable.
- **Verbosity** — the exception hierarchy (6 classes for upstream errors) and the builder/DTO
  scaffolding adds lines without adding clarity.
- **No native channels** — `CompletableFuture.allOf(teamsFuture, lineupsFuture)` works but is
  harder to reason about under error conditions than a `select` over Go channels.

---

## 4. Component-by-Component Mapping

### 4.1 Concurrency — Parallel Enrichment

**Java (`AbiosSnapshotReader`)**
```java
private final Executor enrichmentExecutor = Executors.newVirtualThreadPerTaskExecutor();

CompletableFuture<List<AbiosTeamNode>> teamsFuture = CompletableFuture.supplyAsync(
    () -> fetchInChunks("teamBatches", teamIds, resilient::fetchTeams, calls),
    enrichmentExecutor);

CompletableFuture<List<AbiosRosterNode>> lineupsFuture = CompletableFuture.supplyAsync(
    () -> fetchInChunks("lineupBatches", lineupIds, resilient::fetchLineups, calls),
    enrichmentExecutor);

CompletableFuture.allOf(teamsFuture, lineupsFuture).join();
```

**Go equivalent**
```go
type enrichResult[T any] struct {
    items []T
    err   error
}

teamsCh  := make(chan enrichResult[TeamNode], 1)
lineupsCh := make(chan enrichResult[RosterNode], 1)

go func() { items, err := fetchInChunks(teamIDs, fetchTeams); teamsCh <- enrichResult[TeamNode]{items, err} }()
go func() { items, err := fetchInChunks(lineupIDs, fetchLineups); lineupsCh <- enrichResult[RosterNode]{items, err} }()

tr := <-teamsCh
lr := <-lineupsCh
if tr.err != nil { return nil, tr.err }
if lr.err != nil { return nil, lr.err }
```

**Why Go wins here**: goroutines start in ~2 µs vs virtual-thread park/unpark overhead. Error
propagation through channels is explicit. `select` lets you add a `ctx.Done()` case for free
cancellation without changing call sites.

---

### 4.2 HTTP Client & Error Handling

**Java (`AbiosDataGateway`)**
```java
byte[] payload = abiosRestClient.get().uri(uri).headers(this::setAuth)
    .retrieve()
    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
        if (status == HttpStatus.TOO_MANY_REQUESTS) throw new AbiosUpstreamRateLimitedException(...);
        throw new AbiosUpstreamClientException(...);
    })
    .body(byte[].class);
```

**Go equivalent**
```go
func (c *AbiosClient) fetchSeries(ctx context.Context, take, skip int) (*EnrichedDoc, error) {
    req, _ := http.NewRequestWithContext(ctx, http.MethodGet, c.buildURL(take, skip), nil)
    req.Header.Set(c.cfg.AuthHeaderName, c.authValue())

    resp, err := c.http.Do(req)
    if err != nil { return nil, &TransientError{err} }
    defer resp.Body.Close()

    switch resp.StatusCode {
    case http.StatusTooManyRequests:
        return nil, &RateLimitedError{RetryAfter: parseRetryAfter(resp)}
    case http.StatusUnauthorized, http.StatusForbidden:
        return nil, &AuthError{Code: resp.StatusCode}
    }
    if resp.StatusCode >= 500 {
        return nil, &TransientError{fmt.Errorf("upstream %d", resp.StatusCode)}
    }
    // parse body...
}
```

**Why Go wins here**: errors are values — the call chain is a plain `if err != nil` ladder.
No exception hierarchy needed. `context.Context` propagation gives deadline/cancellation for free.
The Java version needs 6 exception subclasses; Go needs 3 sentinel structs with `errors.As`.

---

### 4.3 Resilience (Retry + Circuit Breaker + Rate Limiter)

**Java (`ResilientAbiosFetch`)**
```java
@Retry(name = "abios")
@RateLimiter(name = "abiosOut")
@CircuitBreaker(name = "abios")
public AbiosEnrichedDocument fetchSeries(int take, int skip) {
    return gateway.fetchSeries(take, skip);
}
```

**Go equivalent**
```go
// Rate limiter — stdlib
limiter := rate.NewLimiter(rate.Every(time.Minute/300), 300) // 300 rpm

// Circuit breaker — sony/gobreaker
cb := gobreaker.NewCircuitBreaker(gobreaker.Settings{
    MaxRequests: 1,
    Interval:    10 * time.Second,
    Timeout:     30 * time.Second,
    ReadyToTrip: func(c gobreaker.Counts) bool { return c.ConsecutiveFailures > 5 },
})

func (c *ResilientClient) FetchSeries(ctx context.Context, take, skip int) (*EnrichedDoc, error) {
    if err := c.limiter.Wait(ctx); err != nil { return nil, err }
    result, err := c.cb.Execute(func() (interface{}, error) {
        return c.retryWithBackoff(ctx, func() (*EnrichedDoc, error) {
            return c.gateway.FetchSeries(ctx, take, skip)
        })
    })
    if err != nil { return nil, err }
    return result.(*EnrichedDoc), nil
}
```

**Trade-off**: Java's annotation approach is zero-boilerplate but invisible — you can't see the
policy at the call site. Go's explicit wiring is 15 more lines but instantly readable and testable
without Spring AOP."

---

### 4.4 Cache — Stale-While-Revalidate

**Java (`LiveDataService`)**
```java
private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
private final Object refreshLock = new Object();

public LiveSnapshot getSnapshot() {
    LiveSnapshot snap = snapshotCache.get().orElse(null);
    if (snap == null) { return refreshBlocking("request-initial"); }
    if (isFresh(snap)) { return snap.asFresh(); }
    triggerAsyncRefresh("request-stale");
    return snap.asStale();
}
```

**Go equivalent**
```go
type cache struct {
    mu       sync.RWMutex
    snapshot *Snapshot
    loading  atomic.Bool
}

func (c *cache) Get(ctx context.Context, refresh func() (*Snapshot, error)) (*Snapshot, error) {
    c.mu.RLock()
    snap := c.snapshot
    c.mu.RUnlock()

    if snap == nil { return c.load(ctx, refresh) }
    if snap.IsFresh() { return snap, nil }

    if c.loading.CompareAndSwap(false, true) {
        go func() {
            defer c.loading.Store(false)
            if s, err := refresh(); err == nil { c.store(s) }
        }()
    }
    return snap.AsStale(), nil
}
```

**Why they're equivalent**: `AtomicBoolean.compareAndSet` ↔ `atomic.Bool.CompareAndSwap`.
`synchronized(refreshLock)` ↔ `sync.Mutex`. `AtomicReference<LiveSnapshot>` ↔ `sync.RWMutex` + pointer.
Go's primitives are in the standard library; Java needs Spring + Lombok to reduce the ceremony.

---

### 4.5 Background Refresh

**Java**
```java
@Scheduled(fixedDelayString = "${app.live.background-refresh-ms:15000}")
public void tick() { liveDataService.refreshInBackground(); }
```

**Go**
```go
func (j *RefreshJob) Start(ctx context.Context, interval time.Duration) {
    go func() {
        ticker := time.NewTicker(interval)
        defer ticker.Stop()
        for {
            select {
            case <-ticker.C:
                j.svc.RefreshInBackground(ctx)
            case <-ctx.Done():
                return
            }
        }
    }()
}
```

**Key difference**: Go's version participates in graceful shutdown automatically via `ctx.Done()`.
Spring's `@Scheduled` needs `@PreDestroy` or `TaskScheduler.shutdown()` wiring to achieve the same.

---

### 4.6 Per-IP Rate Limiting

**Java (`IpRateLimitFilter`)**: Bucket4j (token bucket) + Caffeine per-IP map, Redis-backed when
`cache-mode=redis`. ~125 lines including servlet boilerplate.

**Go equivalent**
```go
// stdlib rate limiter, one per IP
var mu sync.Mutex
var buckets = make(map[string]*rate.Limiter)

func limiterFor(ip string, rps rate.Limit, burst int) *rate.Limiter {
    mu.Lock()
    defer mu.Unlock()
    if l, ok := buckets[ip]; ok { return l }
    l := rate.NewLimiter(rps, burst)
    buckets[ip] = l
    return l
}

func RateLimitMiddleware(rps rate.Limit, burst int) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            l := limiterFor(clientIP(r), rps, burst)
            if !l.Allow() {
                http.Error(w, "429 Too Many Requests", http.StatusTooManyRequests)
                return
            }
            next.ServeHTTP(w, r)
        })
    }
}
```

~30 lines, zero external dependencies. Add `golang.org/x/time/rate`. For distributed mode swap
to Redis via `go-redis` + a Lua script — same interface.

---

### 4.7 Configuration

**Java**: `@ConfigurationProperties(prefix = "abios")` + Spring profiles + YAML.

**Go**:
```go
type Config struct {
    BaseURL        string        `env:"ABIOS_BASE_URL"       default:"https://api.abiosgaming.com"`
    APIKey         string        `env:"ABIOS_API_KEY"`
    ConnectTimeout time.Duration `env:"ABIOS_CONNECT_TIMEOUT" default:"3s"`
    MockEnabled    bool          `env:"ABIOS_MOCK_ENABLED"   default:"true"`
}
// Parse with: github.com/caarlos0/env or github.com/spf13/viper
```

No profiles needed — just environment variables. Simpler for containers.

---

## 5. Where Go Would Be a Better Fit (and Why)

| Area | Reason |
|---|---|
| Parallel enrichment | Goroutines + `errgroup` are simpler than `CompletableFuture.allOf` for fan-out/fan-in with error propagation |
| Upstream HTTP calls | `context.Context` propagation gives unified timeout/cancellation across all outgoing calls without extra wiring |
| Startup speed | Go cold-starts in <200 ms — important if this service gets autoscaled or deployed to Lambda/Cloud Run |
| Binary distribution | Single static binary with no JVM dependency — trivial to containerize (`FROM scratch`) |
| Memory efficiency | Goroutine stack starts at 2 KB vs JVM thread overhead; serving 1000 concurrent requests costs far less RAM |
| Resilience visibility | Retry/circuit-breaker logic is explicit code, not annotation-driven magic — easier to test and reason about |


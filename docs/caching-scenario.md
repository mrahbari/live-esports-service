# Caching Architecture and Strategy

This document outlines the caching strategy implemented in the Live Esports Service to ensure high availability, low latency, and resilience against upstream API failures.

## 0. Technical Approach: Snapshot Track (V1)

The "Snapshot Track" is the architectural pattern used to manage live esports data. It aims to deliver a unified, enriched view of all active matches with near-zero latency.

### Core Philosophy
1.  **Stale-While-Revalidate:** Prioritize availability and speed by serving cached data (even if stale) while asynchronously refreshing it in the background.
2.  **Parallel Enrichment:** Optimize the expensive upstream data collection process by parallelizing independent fetch operations.
3.  **Proactive Warming:** Ensure the cache is never empty by fetching data during startup and maintaining it via scheduled jobs.

### The 6-Phase Enrichment Pipeline
Building a full `LiveSnapshot` involves a coordinated sequence of calls to the Abios Atlas API:
1.  **Series Fetch:** Retrieves the base list of live series (Sequential).
2.  **ID Extraction:** Processes metadata and extracts related IDs (In-memory).
3.  **Rosters Fetch:** Discovers teams and active lineups (Sequential).
4.  **Parallel Teams & Lineups:** Fetches detailed team and lineup data concurrently using **Java 21 Virtual Threads** (Saves ~20% wall-clock time).
5.  **Players Fetch:** Retrieves individual player statistics (Sequential).
6.  **Immutable Aggregation:** Merges all data into a thread-safe `LiveSnapshot` object.

---

## 1. Core Strategy: Stale-While-Revalidate
The system employs a "Stale-While-Revalidate" pattern. It prioritizes serving data from the cache, even if slightly expired, while triggering a background update to fetch fresh data.

### Key Logic in `LiveDataService.java`
```java
public LiveSnapshot getSnapshot() {
    LiveSnapshot snap = snapshotCache.get().orElse(null);
    
    // 1. Cache Miss (Cold Start)
    if (snap == null) {
        return refreshBlocking("request-initial");
    }

    // 2. Cache Hit (Fresh)
    if (!isExpired(snap)) {
        return snap.asFresh();
    }

    // 3. Stale-While-Revalidate
    if (isWithinStaleTtl(snap)) {
        triggerAsyncRefresh("request-stale");
        return snap.asStale();
    }

    // 4. Cache Expired (Hard Expiry)
    return refreshBlocking("request-expired");
}
```

## 2. Configuration Settings
Caching behavior is controlled via `src/main/resources/application.yml`:

```yaml
app:
  live:
    cache-mode: ${CACHE_MODE:memory} # Options: memory | redis
    cache-ttl-seconds: 20            # How long data is considered "Fresh"
    background-refresh-ms: 15000     # Frequency of background check
    background-refresh-enabled: true
    max-stale-ttl-seconds: 300       # Max time to serve stale data during outages
```

## 3. Cache Warming (Pre-warming)
To prevent latency for the first user after a service restart, the system eagerly warms the cache during startup.

### Implementation in `LiveEsportsApplication.java`
```java
@Bean
public CommandLineRunner eagerLoad(LiveDataService service) {
    // Triggers an initial fetch immediately after Spring Context is ready
    return args -> service.refreshInBackground();
}
```

## 4. Background Refresh Mechanism
A dedicated job ensures the cache stays warm and updated independently of user requests.

### Implementation in `LiveDataRefreshJob.java`
```java
@Scheduled(fixedDelayString = "${app.live.background-refresh-ms:15000}")
public void tick() {
    // Checks if the snapshot is expired and refreshes if necessary
    liveDataService.refreshInBackground();
}
```

## 5. Resilience Features
- **Adaptive TTL:** The service slightly extends the TTL if no live series are currently active to reduce unnecessary upstream pressure.
- **Distributed Locking:** When using Redis, a distributed lock (`lock:live-refresh`) ensures only one instance of the service performs the refresh at a time.
- **Graceful Fallback:** If the Abios API is down, the service continues to serve the last known good snapshot for up to `max-stale-ttl-seconds`.

## 6. Cache Implementation Types

The system provides two implementation choices for the `LiveSnapshotCache` interface, allowing flexibility between local development and distributed production environments.

### 1. In-Memory Cache (`InMemoryLiveSnapshotCache`)
This is the default implementation. It uses a thread-safe `AtomicReference` to store the latest snapshot.
- **Purpose:** Ideal for local development, unit testing, or single-instance deployments.
- **Pros:** Zero latency (no network hop), no external dependencies.
- **Cons:** Data is lost on restart; not shared across multiple service instances.

### 2. Redis-Backed Cache (`RedisLiveSnapshotCache`)
A more sophisticated implementation designed for distributed systems (Microservices).
- **Hybrid L1/L2 Architecture:** 
    - **L1 (Local):** A very short-lived (2s) local memory cache to prevent "Redis hammering" during high-traffic spikes.
    - **L2 (Distributed):** Redis stores the canonical snapshot shared by all service instances.
- **Purpose:** Essential for horizontally scaled deployments where all nodes must serve consistent data.
- **Resilience:** Survives application restarts; centralizes the data state.

---

## Comparison Summary

| Feature | InMemory | Redis (L1+L2) |
| :--- | :--- | :--- |
| **Best For** | Local Dev / Single Instance | Production / Distributed Systems |
| **Consistency** | Local to node | Shared across all nodes |
| **Persistence** | None (Lost on restart) | High (Persistent in Redis) |
| **Complexity** | Minimal | Medium (Requires Redis) |
| **Performance** | Nano-seconds | Micro-seconds (L1 makes it very fast) |

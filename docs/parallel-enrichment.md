# Parallel Enrichment — Atlas Snapshot Fetch Optimisation

## Problem

Building a `LiveSnapshot` requires 6 sequential round-trips to the Atlas API:

```
1. Series pages     (paginated, must be first)
2. ID extraction    (in-memory, no network)
3. Rosters          (must be after series — discovers teamIds and lineupIds)
4. Lineups          (must be after rosters — lineupIds are now final)
5. Teams            (must be after rosters — teamIds are now final)
6. Players          (must be after lineups — playerIds are now final)
```

In the original implementation **all six phases are strictly sequential**, including phases 4 and 5 which have no dependency on each other. Under a typical 200 ms round-trip per batch group, the total wall-clock time to build a snapshot is roughly:

```
T_series + T_rosters + T_lineups + T_teams + T_players   ≈  5 × RTT
```

---

## Solution

After rosters complete, **teams and lineups are independent**:

- `teamIds` is final — rosters were the last source of new team IDs.
- `lineupIds` is final — rosters were the last source of new lineup IDs.
- Neither fetch reads or modifies the other's ID set.

They are dispatched concurrently on **Java 21 virtual threads** and joined before players starts:

```
1. Series     ──sequential──►
2. ID extract ──in-memory──►
3. Rosters    ──sequential──►
                             ├── Teams   ──virtual thread──┐
                             └── Lineups ──virtual thread──┤
                                                           ▼
                                                      allOf().join()
                                                           │
                                              merge playerIds from lineups
                                                           │
4. Players    ──sequential──────────────────────────────► ▼
```

Wall-clock time after the change:

```
T_series + T_rosters + max(T_lineups, T_teams) + T_players   ≈  4 × RTT
```

One full batch group (the slower of teams or lineups) is saved per snapshot rebuild.

---

## Changed Files

### `AbiosSnapshotReader.java`

- Added `private final Executor enrichmentExecutor = Executors.newVirtualThreadPerTaskExecutor()`.
- Phases 4+5 replaced with two `CompletableFuture.supplyAsync()` calls on `enrichmentExecutor`.
- `CompletableFuture.allOf(...).join()` gates the players fetch until both complete.

No new Spring beans, no constructor changes — the executor is owned by the component.

### `AbiosEnrichedDocument.java`

- `atlasCalls` changed from `HashMap` to `ConcurrentHashMap`.
- Both virtual threads call `calls.merge(callType, 1, Integer::sum)` concurrently. `ConcurrentHashMap.merge()` is atomic; `HashMap.merge()` is not.

---

## Why Virtual Threads

Both enrichment tasks are pure I/O — they block on HTTP responses from Atlas. Virtual threads (Project Loom, available since Java 21) are ideal here:

- Each task is a lightweight thread with no carrier-thread pinning during blocking I/O.
- No fixed thread pool sizing required — the executor creates as many virtual threads as needed.
- The Resilience4j `@RateLimiter(name = "abiosOut")` AOP advice still fires per call, ensuring the outbound rate limit is respected regardless of how many threads are in flight.

---

## Dependency Constraints (Why Not More Parallelism)

| Phase | Depends on | Can parallelise with |
|---|---|---|
| Series | nothing | nothing (must be first) |
| Rosters | series IDs | nothing |
| Teams | roster results (more teamIds) | Lineups |
| Lineups | roster results (more lineupIds) | Teams |
| Players | lineup results (more playerIds) | nothing (must be last) |

Rosters → Teams/Lineups is a hard dependency. Players → Lineups is a hard dependency. The only safe parallel pair is **Teams ‖ Lineups**, which is what this change implements.

---

## Rate Limit Impact

The `abiosOut` Resilience4j rate limiter (300 req/min in production) gates every outgoing Atlas call via AOP. Running teams and lineups in parallel means **both call groups contend for the same rate-limit bucket simultaneously** instead of one after the other.

In practice this is acceptable:
- The parallelism is bounded to exactly 2 concurrent batch groups.
- Each group itself is still sequential inside `fetchInChunks`.
- At `batchSize=50` and typical live load (< 20 series), the total call count per enrichment phase rarely exceeds 1–2 batches, well within the 300 req/min budget.
- If Atlas is under pressure, the rate limiter will naturally throttle one of the threads.

---

## Before / After Timeline (Example: 3 teams batches, 2 lineup batches)

**Before**
```
|--series--|--rosters--|--lineups (2)--|--teams (3)--|--players--|
 ~200ms      ~200ms       ~400ms          ~600ms       ~200ms
 Total: ~1600ms
```

**After**
```
|--series--|--rosters--|--teams (3)-----|
                       |--lineups (2)--|--players--|
 ~200ms      ~200ms      ~600ms           ~200ms
 Total: ~1200ms   (saves ~400ms = the lineups batch group)
```

The saving equals `min(T_teams, T_lineups)` per rebuild cycle.

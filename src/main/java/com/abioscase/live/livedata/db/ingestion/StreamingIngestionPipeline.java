package com.abioscase.live.livedata.db.ingestion;

import com.abioscase.live.config.AbiosProperties;
import com.abioscase.live.config.IngestionProperties;
import com.abioscase.live.integration.abios.AbiosResilientFetch;
import com.abioscase.live.integration.abios.model.*;
import com.abioscase.live.livedata.db.repository.JdbcLiveRepository;
import com.abioscase.live.livedata.polling.SeriesWindowFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Orchestrates the streaming ingestion process from Abios to PostgreSQL.
 * Only runs when mock mode is disabled — mock data belongs in the V1 in-memory cache, not the DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingIngestionPipeline {

    private final AbiosProperties abios;
    private final IngestionProperties ingestion;
    private final AbiosResilientFetch resilient;
    private final JdbcLiveRepository repository;

    private final Executor enrichExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Transactional
    public void runIngestion() {
        Instant cycleStart = Instant.now();
        log.info("Starting ingestion cycle at {}", cycleStart);

        runStreamingIngestion(cycleStart);

        int pruned = repository.pruneStaleEnriched(cycleStart);
        if (pruned > 0) log.info("Pruned {} stale series records", pruned);
        log.info("Ingestion cycle complete");
    }

    private void runStreamingIngestion(Instant cycleStart) {
        Map<String, Set<String>> teamSeries   = new LinkedHashMap<>();
        Map<String, Set<String>> playerSeries = new LinkedHashMap<>();
        Map<String, Set<String>> teamPlayers  = new LinkedHashMap<>();
        Map<String, Set<String>> rosterSeries = new LinkedHashMap<>();
        Set<String> rosterIds = new LinkedHashSet<>();

        int skip = 0, take = abios.getBatchSize(), pages = 0;
        while (pages < abios.getMaxSeriesPages()) {
            pages++;
            AbiosEnrichedDocument pageDoc = resilient.fetchSeriesWithQuery(take, skip, abios.getLivePollingQuery());
            List<AbiosSeriesNode> page = pageDoc.allSeries();
            if (page == null || page.isEmpty()) break;

            // Batch-fetch resource versions for this page to decide which series need enrichment.
            List<String> pageIds = page.stream().map(AbiosSeriesNode::getId).filter(Objects::nonNull).toList();
            Map<String, Integer> storedVersions = repository.findResourceVersions(pageIds);

            for (AbiosSeriesNode s : page) {
                if (s.getId() == null) continue;
                Instant start = SeriesWindowFilter.parseInstant(s.getStartedAt());
                if (!SeriesWindowFilter.isWithinWindowLive(start, cycleStart, ingestion.getWindowDays())) {
                    log.info("Skipping series {} outside time window", s.getId());
                    continue;
                }

                // Always upsert metadata to keep updated_at fresh for staleness/pruning.
                repository.upsertEnrichedSeries(s.getId(), s.displayName(), s.getGameName(), s.getState(),
                        s.getStartedAt(), s.getTier(), s.getBestOf(),
                        countTeamsWithId(s.allTeams()), s.getResourceVersion(), cycleStart);

                // Skip enrichment if resource_version hasn't changed since the last cycle.
                Integer storedVersion = storedVersions.get(s.getId());
                if (storedVersion != null && storedVersion.equals(s.getResourceVersion())) {
                    log.debug("Series {} unchanged (resource_version={}), skipping enrichment", s.getId(), storedVersion);
                    continue;
                }

                for (AbiosTeamNode t : s.allTeams()) {
                    String tid = t.effectiveTeamId();
                    if (tid != null) {
                        teamSeries.computeIfAbsent(tid, k -> new LinkedHashSet<>()).add(s.getId());
                    }
                    String rid = t.rosterLinkId();
                    if (rid != null) {
                        rosterIds.add(rid);
                        rosterSeries.computeIfAbsent(rid, k -> new LinkedHashSet<>()).add(s.getId());
                    }
                    for (AbiosPlayerNode p : t.allPlayers()) {
                        if (p.getId() != null) {
                            playerSeries.computeIfAbsent(p.getId(), k -> new LinkedHashSet<>()).add(s.getId());
                            if (tid != null) {
                                teamPlayers.computeIfAbsent(tid, k -> new LinkedHashSet<>()).add(p.getId());
                            }
                        }
                    }
                }
            }
            if (page.size() < take) break;
            skip += take;
        }

        if (!rosterIds.isEmpty()) enrichRosters(rosterIds, rosterSeries, teamSeries, playerSeries, teamPlayers);

        CompletableFuture<Void> teamsFuture = teamSeries.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.runAsync(() -> enrichTeams(teamSeries, teamPlayers, cycleStart), enrichExecutor);

        CompletableFuture<Void> playersFuture = playerSeries.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.runAsync(() -> enrichPlayers(playerSeries, cycleStart), enrichExecutor);

        CompletableFuture.allOf(teamsFuture, playersFuture).join();
    }

    private void enrichRosters(Set<String> rosterIds,
                                Map<String, Set<String>> rosterSeries,
                                Map<String, Set<String>> teamSeries,
                                Map<String, Set<String>> playerSeries,
                                Map<String, Set<String>> teamPlayers) {
        List<String> ids = new ArrayList<>(rosterIds);
        for (int i = 0; i < ids.size(); i += abios.getBatchSize()) {
            Set<String> chunk = new LinkedHashSet<>(ids.subList(i, Math.min(i + abios.getBatchSize(), ids.size())));
            List<AbiosRosterNode> rosters = resilient.fetchRosters(chunk);
            if (rosters == null) continue;
            for (AbiosRosterNode r : rosters) {
                if (r.getId() == null) continue;

                // Series IDs this roster belongs to (from when the series page was processed)
                Set<String> sids = rosterSeries.getOrDefault(r.getId(), Set.of());

                if (r.getTeam() != null && r.getTeam().getId() != null) {
                    String tid = r.getTeam().getId();
                    // Register the team→series mapping so enrichTeams() can use it
                    teamSeries.computeIfAbsent(tid, k -> new LinkedHashSet<>()).addAll(sids);
                }

                List<AbiosPlayerNode> rosterPlayers = r.getLineUp() != null && r.getLineUp().getPlayers() != null
                        ? r.getLineUp().getPlayers()
                        : r.getPlayers() != null ? r.getPlayers() : List.of();

                for (AbiosPlayerNode p : rosterPlayers) {
                    if (p.getId() == null) continue;
                    playerSeries.computeIfAbsent(p.getId(), k -> new LinkedHashSet<>()).addAll(sids);
                    if (r.getTeam() != null && r.getTeam().getId() != null) {
                        teamPlayers.computeIfAbsent(r.getTeam().getId(), k -> new LinkedHashSet<>()).add(p.getId());
                    }
                }
            }
        }
    }

    private void enrichTeams(Map<String, Set<String>> teamSeries,
                              Map<String, Set<String>> teamPlayers,
                              Instant now) {
        List<String> ids = new ArrayList<>(teamSeries.keySet());
        for (int i = 0; i < ids.size(); i += abios.getBatchSize()) {
            Set<String> chunk = new LinkedHashSet<>(ids.subList(i, Math.min(i + abios.getBatchSize(), ids.size())));
            List<AbiosTeamNode> teams = resilient.fetchTeams(chunk);
            if (teams == null) continue;
            for (AbiosTeamNode t : teams) {
                if (t.effectiveTeamId() == null) continue;
                String tid = t.effectiveTeamId();
                Set<String> sids = teamSeries.getOrDefault(tid, Set.of());
                int playerCount = teamPlayers.getOrDefault(tid, Set.of()).size();
                repository.upsertEnrichedTeam(tid, t.effectiveTeamName(), t.getAbbreviation(),
                        playerCount, sids, now);
            }
        }
    }

    private void enrichPlayers(Map<String, Set<String>> playerSeries, Instant now) {
        List<String> ids = new ArrayList<>(playerSeries.keySet());
        for (int i = 0; i < ids.size(); i += abios.getBatchSize()) {
            Set<String> chunk = new LinkedHashSet<>(ids.subList(i, Math.min(i + abios.getBatchSize(), ids.size())));
            List<AbiosPlayerNode> players = resilient.fetchPlayers(chunk);
            if (players == null) continue;
            for (AbiosPlayerNode p : players) {
                if (p.getId() == null) continue;
                Set<String> sids = playerSeries.getOrDefault(p.getId(), Set.of());
                repository.upsertEnrichedPlayer(p.getId(), p.effectiveNickname(), p.getFirstName(),
                        p.getLastName(), p.getRole(), null, null, sids, now);
            }
        }
    }

    private static int countTeamsWithId(List<AbiosTeamNode> teams) {
        return (int) teams.stream().filter(t -> t.effectiveTeamId() != null).count();
    }
}

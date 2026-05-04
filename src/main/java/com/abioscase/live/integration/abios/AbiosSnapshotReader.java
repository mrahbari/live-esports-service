package com.abioscase.live.integration.abios;

import com.abioscase.live.config.AbiosProperties;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosSeriesNode;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbiosSnapshotReader {

    private final AbiosProperties abios;
    private final AbiosDataGateway gateway;
    private final AbiosResilientFetch resilient;

    // One virtual thread per enrichment task — cheap for I/O-bound Atlas calls.
    private final Executor enrichmentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AbiosEnrichedDocument loadRawSeries() {
        if (abios.isV1MockEnabled()) {
            AbiosEnrichedDocument doc = gateway.readMock();
            List<AbiosRosterNode> mockRosters = gateway.readMockRosters();
            if (mockRosters != null && !mockRosters.isEmpty()) {
                log.info("Enriching mock snapshot with {} rosters", mockRosters.size());
                doc.setRosters(mockRosters);
            }
            return doc;
        }

        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        Map<String, Integer> calls = doc.getAtlasCalls();

        // 1. Fetch series (paginated)
        List<AbiosSeriesNode> allSeries = fetchAllSeries(calls);
        doc.setSeries(allSeries);

        // 2. Extract IDs
        Set<String> rosterIds = new LinkedHashSet<>();
        Set<String> teamIds = new LinkedHashSet<>();
        Set<String> playerIds = new LinkedHashSet<>();
        Set<String> lineupIds = new LinkedHashSet<>();

        for (AbiosSeriesNode s : allSeries) {
            if (s.getLineups() != null) {
                for (AbiosRosterNode.LineUp l : s.getLineups()) {
                    if (l.getId() != null) lineupIds.add(l.getId());
                    mergePlayerIdsFromLineup(l, playerIds);
                }
            }
            for (AbiosTeamNode t : s.allTeams()) {
                if (t.effectiveTeamId() != null) {
                    teamIds.add(t.effectiveTeamId());
                }
                String rosterEdgeId = t.rosterLinkId();
                if (rosterEdgeId != null) {
                    rosterIds.add(rosterEdgeId);
                }
                if (t.getLineUp() != null && t.getLineUp().getId() != null) {
                    lineupIds.add(t.getLineUp().getId());
                    mergePlayerIdsFromLineup(t.getLineUp(), playerIds);
                }
                for (AbiosPlayerNode p : t.allPlayers()) {
                    if (p.getId() != null) playerIds.add(p.getId());
                }
            }
        }

        // 3. Batch resolve Rosters
        if (!rosterIds.isEmpty()) {
            List<AbiosRosterNode> rosters = fetchInChunks("rosterBatches", rosterIds, resilient::fetchRosters, calls);
            doc.setRosters(rosters);
            for (AbiosRosterNode r : rosters) {
                if (r.getTeam() != null && r.getTeam().getId() != null) {
                    teamIds.add(r.getTeam().getId());
                }
                if (r.getLineUp() != null && r.getLineUp().getId() != null) {
                    lineupIds.add(r.getLineUp().getId());
                }
                mergePlayerIdsFromRosterNode(r, playerIds);
            }
        }

        // 4+5. Teams and Lineups are independent of each other after rosters — run in parallel.
        // Teams: teamIds is final (rosters were the last source of new team IDs).
        // Lineups: lineupIds is final for the same reason.
        // Players (phase 6) must still wait for lineups because lineups may add more playerIds.
        CompletableFuture<List<AbiosTeamNode>> teamsFuture = teamIds.isEmpty()
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.supplyAsync(
                        () -> fetchInChunks("teamBatches", teamIds, resilient::fetchTeams, calls),
                        enrichmentExecutor);

        CompletableFuture<List<AbiosRosterNode>> lineupsFuture = lineupIds.isEmpty()
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.supplyAsync(
                        () -> fetchInChunks("lineupBatches", lineupIds, resilient::fetchLineups, calls),
                        enrichmentExecutor);

        CompletableFuture.allOf(teamsFuture, lineupsFuture).join();
        doc.setTeamsEnriched(teamsFuture.join());

        // Drain lineup results to finalise playerIds before starting the players fetch.
        for (AbiosRosterNode l : lineupsFuture.join()) {
            mergePlayerIdsFromRosterNode(l, playerIds);
        }

        // 6. Batch resolve Players — starts only after lineups complete (playerIds now final).
        if (!playerIds.isEmpty()) {
            doc.setPlayersEnriched(fetchInChunks("playerBatches", playerIds, resilient::fetchPlayers, calls));
        }

        calls.put("total", calls.values().stream().mapToInt(Integer::intValue).sum());
        return doc;
    }

    private static void mergePlayerIdsFromLineup(AbiosRosterNode.LineUp lineup, Set<String> playerIds) {
        if (lineup != null && lineup.getPlayers() != null) {
            for (AbiosPlayerNode p : lineup.getPlayers()) {
                if (p.getId() != null) playerIds.add(p.getId());
            }
        }
    }

    private static void mergePlayerIdsFromRosterNode(AbiosRosterNode node, Set<String> playerIds) {
        if (node == null) {
            return;
        }
        if (node.getPlayers() != null) {
            for (AbiosPlayerNode p : node.getPlayers()) {
                if (p.getId() != null) playerIds.add(p.getId());
            }
        }
        if (node.getLineUp() != null && node.getLineUp().getPlayers() != null) {
            for (AbiosPlayerNode p : node.getLineUp().getPlayers()) {
                if (p.getId() != null) playerIds.add(p.getId());
            }
        }
    }

    private List<AbiosSeriesNode> fetchAllSeries(Map<String, Integer> calls) {
        List<AbiosSeriesNode> all = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int skip = 0;
        int take = 100;
        int pages = 0;

        while (pages < abios.getMaxSeriesPages()) {
            pages++;
            calls.merge("seriesPages", 1, Integer::sum);
            
            AbiosEnrichedDocument pageDoc = resilient.fetchSeries(take, skip);
            List<AbiosSeriesNode> page = pageDoc.allSeries();
            
            if (page == null || page.isEmpty()) {
                break;
            }

            for (AbiosSeriesNode s : page) {
                if (s.getId() != null && seenIds.add(s.getId())) {
                    all.add(s);
                } else if (s.getId() != null) {
                    log.debug("Skipping duplicate series ID: {} (likely caused by concurrent data shift)", s.getId());
                }
            }

            if (page.size() < take) {
                break;
            }
            skip += take;
        }
        return all;
    }

    private <T> List<T> fetchInChunks(String callType, Set<String> ids, java.util.function.Function<Set<String>, List<T>> fetcher, Map<String, Integer> calls) {
        List<T> all = new ArrayList<>();
        List<String> idList = new ArrayList<>(ids);
        int batchSize = abios.getBatchSize();
        
        for (int i = 0; i < idList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, idList.size());
            Set<String> chunk = new LinkedHashSet<>(idList.subList(i, end));
            calls.merge(callType, 1, Integer::sum);
            List<T> result = fetcher.apply(chunk);
            if (result != null) {
                all.addAll(result);
            }
        }
        return all;
    }
}

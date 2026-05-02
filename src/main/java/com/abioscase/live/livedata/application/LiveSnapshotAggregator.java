package com.abioscase.live.livedata.application;

import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosSeriesNode;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps Atlas-shaped documents into a denormalized, de-duplicated view.
 * Aligned with the "LiveSnapshotAggregator" requirement.
 */
@Slf4j
@Component
public class LiveSnapshotAggregator {

    public LiveSnapshot aggregate(AbiosEnrichedDocument doc) {
        Instant asOf = Instant.now();
        if (doc == null) {
            return new LiveSnapshot(asOf, false, false, null, List.of(), List.of(), List.of(), Map.of());
        }
        
        List<AbiosSeriesNode> all = doc.allSeries();
        int estimatedSize = all.size();
        List<LiveSeriesItem> series = new ArrayList<>(estimatedSize);
        Map<String, TeamBuild> teams = new LinkedHashMap<>(estimatedSize * 2);
        Map<String, PlayerBuild> players = new LinkedHashMap<>(estimatedSize * 10);

        // Map enriched data for lookup
        Map<String, AbiosRosterNode> rosterLookup = new HashMap<>();
        if (doc.getRosters() != null) {
            for (AbiosRosterNode r : doc.getRosters()) {
                if (r.getId() != null) rosterLookup.put(r.getId(), r);
            }
        }

        Map<String, AbiosTeamNode> teamLookup = new HashMap<>();
        if (doc.getTeamsEnriched() != null) {
            for (AbiosTeamNode t : doc.getTeamsEnriched()) {
                if (t.effectiveTeamId() != null) teamLookup.put(t.effectiveTeamId(), t);
            }
        }

        Map<String, AbiosPlayerNode> playerLookup = new HashMap<>();
        if (doc.getPlayersEnriched() != null) {
            for (AbiosPlayerNode p : doc.getPlayersEnriched()) {
                if (p.getId() != null) playerLookup.put(p.getId(), p);
            }
        }

        for (AbiosSeriesNode s : all) {
            if (s.getId() == null) continue;
            
            List<AbiosTeamNode> teamNodes = s.allTeams();
            series.add(new LiveSeriesItem(
                    s.getId(),
                    s.displayName(),
                    s.getGameName(),
                    s.getState(),
                    s.getStartedAt(),
                    s.getTier(),
                    s.getBestOf(),
                    countTeamsWithIdentity(teamNodes, rosterLookup)));

            for (AbiosTeamNode t : teamNodes) {
                String tid = resolveTeamSlotId(t, rosterLookup);
                if (tid == null) continue;

                // 1. Resolve Team Name & Players from multiple sources
                AbiosTeamNode enrichedTeam = teamLookup.get(tid);
                String tName = resolveTeamName(t, enrichedTeam, rosterLookup);
                String tAbbr = resolveTeamAbbreviation(t, enrichedTeam, rosterLookup);
                List<AbiosPlayerNode> plist = resolvePlayers(t, enrichedTeam, rosterLookup);

                TeamBuild tb = teams.computeIfAbsent(tid, k -> new TeamBuild(tName, tAbbr));
                tb.seriesIds.add(s.getId());
                
                for (AbiosPlayerNode p : plist) {
                    if (p.getId() == null) continue;
                    
                    AbiosPlayerNode enrichedPlayer = playerLookup.get(p.getId());
                    String pNick = resolveNickname(p, enrichedPlayer);
                    String firstName = resolveFirstName(p, enrichedPlayer);
                    String lastName = resolveLastName(p, enrichedPlayer);
                    
                    tb.playerIds.add(p.getId());
                    PlayerBuild pb = players.computeIfAbsent(p.getId(), 
                            k -> new PlayerBuild(pNick, firstName, lastName, p.getRole(), tid, tName));
                    pb.seriesIds.add(s.getId());
                }
            }
        }

        List<LiveTeamItem> teamItems = finalizeTeams(teams);
        List<LivePlayerItem> playerItems = finalizePlayers(players);
        series.sort(Comparator.comparing(LiveSeriesItem::name, Comparator.nullsLast(String::compareTo)));

        return new LiveSnapshot(asOf, false, false, null, series, playerItems, teamItems, doc.getAtlasCalls());
    }

    private String resolveTeamAbbreviation(AbiosTeamNode t, AbiosTeamNode enriched, Map<String, AbiosRosterNode> rosters) {
        if (enriched != null && enriched.getAbbreviation() != null && !enriched.getAbbreviation().isBlank()) return enriched.getAbbreviation();
        
        if (t.getRoster() != null && t.getRoster().getId() != null) {
            AbiosRosterNode r = rosters.get(t.getRoster().getId());
            if (r != null && r.getTeam() != null && r.getTeam().getAbbreviation() != null) return r.getTeam().getAbbreviation();
        }
        
        return t.getAbbreviation();
    }

    private String resolveTeamName(AbiosTeamNode t, AbiosTeamNode enriched, Map<String, AbiosRosterNode> rosters) {
        if (enriched != null && enriched.getName() != null && !enriched.getName().isBlank()) return enriched.getName();
        
        if (t.getRoster() != null && t.getRoster().getId() != null) {
            AbiosRosterNode r = rosters.get(t.getRoster().getId());
            if (r != null && r.getTeam() != null && r.getTeam().getName() != null) return r.getTeam().getName();
        }
        
        String name = t.effectiveTeamName();
        if (name != null && !name.isBlank() && !name.equals("unknown")) return name;
        
        String tid = resolveTeamSlotId(t, rosters);
        return (tid != null) ? "Team " + tid : "unknown";
    }

    private List<AbiosPlayerNode> resolvePlayers(AbiosTeamNode t, AbiosTeamNode enriched, 
                                               Map<String, AbiosRosterNode> rosters) {
        List<AbiosPlayerNode> list = new ArrayList<>();
        
        // Use enriched team players if available
        if (enriched != null && enriched.allPlayers() != null && !enriched.allPlayers().isEmpty()) {
            mergePlayers(list, enriched.allPlayers());
        }
        
        // Use roster enrichment if available
        if (t.getRoster() != null && t.getRoster().getId() != null) {
            AbiosRosterNode r = rosters.get(t.getRoster().getId());
            if (r != null) {
                if (r.getLineUp() != null && r.getLineUp().getPlayers() != null) {
                    mergePlayers(list, r.getLineUp().getPlayers());
                }
                if (r.getPlayers() != null) {
                    mergePlayers(list, r.getPlayers());
                }
            }
        }

        // Fallback to inline players
        if (t.allPlayers() != null && !t.allPlayers().isEmpty()) {
            mergePlayers(list, t.allPlayers());
        }

        return list;
    }

    private void mergePlayers(List<AbiosPlayerNode> target, List<AbiosPlayerNode> source) {
        Set<String> existing = target.stream().map(AbiosPlayerNode::getId).collect(Collectors.toSet());
        for (AbiosPlayerNode s : source) {
            if (s.getId() != null && !existing.contains(s.getId())) {
                target.add(s);
                existing.add(s.getId());
            }
        }
    }

    private String resolveNickname(AbiosPlayerNode p, AbiosPlayerNode enriched) {
        if (enriched != null) {
            String nick = enriched.effectiveNickname();
            if (nick != null && !nick.isBlank()) return nick;
        }
        String nick = p.effectiveNickname();
        if (nick != null && !nick.isBlank() && !nick.equals("unknown")) return nick;
        return (p.getId() != null) ? "Player " + p.getId() : "unknown";
    }

    private String resolveFirstName(AbiosPlayerNode p, AbiosPlayerNode enriched) {
        if (enriched != null && enriched.getFirstName() != null) return enriched.getFirstName();
        return p.getFirstName();
    }

    private String resolveLastName(AbiosPlayerNode p, AbiosPlayerNode enriched) {
        if (enriched != null && enriched.getLastName() != null) return enriched.getLastName();
        return p.getLastName();
    }

    private List<LiveTeamItem> finalizeTeams(Map<String, TeamBuild> teams) {
        List<LiveTeamItem> items = new ArrayList<>();
        for (var e : teams.entrySet()) {
            TeamBuild b = e.getValue();
            items.add(new LiveTeamItem(e.getKey(), b.name, b.abbreviation, b.playerIds.size(), List.copyOf(b.seriesIds)));
        }
        items.sort(Comparator.comparing(LiveTeamItem::name, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    private List<LivePlayerItem> finalizePlayers(Map<String, PlayerBuild> players) {
        List<LivePlayerItem> items = new ArrayList<>();
        for (var e : players.entrySet()) {
            PlayerBuild b = e.getValue();
            items.add(new LivePlayerItem(e.getKey(), b.nickname, b.firstName, b.lastName, b.role, b.teamId, b.teamName, List.copyOf(b.seriesIds)));
        }
        items.sort(Comparator.comparing(LivePlayerItem::nickname, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    /**
     * Team id available either on the slot or via roster enrichment ({@code roster → team.id}).
     */
    private static String resolveTeamSlotId(AbiosTeamNode t, Map<String, AbiosRosterNode> rosterLookup) {
        String direct = t.effectiveTeamId();
        if (direct != null) return direct;
        String rosterId = t.rosterLinkId();
        if (rosterId == null || rosterLookup == null) return null;
        AbiosRosterNode r = rosterLookup.get(rosterId);
        if (r == null || r.getTeam() == null || r.getTeam().getId() == null || r.getTeam().getId().isBlank()) {
            return null;
        }
        return r.getTeam().getId();
    }

    private static int countTeamsWithIdentity(List<AbiosTeamNode> teamNodes, Map<String, AbiosRosterNode> rosterLookup) {
        return (int) teamNodes.stream().filter(t -> resolveTeamSlotId(t, rosterLookup) != null).count();
    }

    private static final class TeamBuild {
        final String name;
        final String abbreviation;
        final Set<String> playerIds = new HashSet<>();
        final TreeSet<String> seriesIds = new TreeSet<>();
        TeamBuild(String name, String abbreviation) { this.name = name; this.abbreviation = abbreviation; }
    }

    private static final class PlayerBuild {
        final String nickname;
        final String firstName;
        final String lastName;
        final String role;
        final String teamId;
        final String teamName;
        final TreeSet<String> seriesIds = new TreeSet<>();
        PlayerBuild(String nickname, String firstName, String lastName, String role, String teamId, String teamName) {
            this.nickname = nickname; this.firstName = firstName; this.lastName = lastName;
            this.role = role; this.teamId = teamId; this.teamName = teamName;
        }
    }
}

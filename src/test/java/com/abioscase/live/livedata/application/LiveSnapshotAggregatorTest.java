package com.abioscase.live.livedata.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosSeriesNode;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import java.util.List;

import org.junit.jupiter.api.Test;

class LiveSnapshotAggregatorTest {

    private final LiveSnapshotAggregator aggregator = new LiveSnapshotAggregator();

    @Test
    void aggregatesAndDeduplicatesTeamsAndPlayersAcrossSeries() {
        AbiosPlayerNode p1 = player("p1", "Ace", null, "carry");
        AbiosTeamNode t1 = team("t1", "Lynx", List.of(p1));

        AbiosSeriesNode s1 = series("s1", "Series 1", List.of(t1));
        AbiosSeriesNode s2 = series("s2", "Series 2", List.of(t1));

        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of(s1, s2));

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.series()).hasSize(2);
        assertThat(snapshot.teams()).hasSize(1);
        assertThat(snapshot.players()).hasSize(1);
        assertThat(snapshot.teams().get(0).seriesIds()).containsExactly("s1", "s2");
        assertThat(snapshot.players().get(0).seriesIds()).containsExactly("s1", "s2");
    }

    @Test
    void skipsEntitiesWithNullIds() {
        AbiosPlayerNode goodPlayer = player("p1", "Ace", null, "carry");
        AbiosPlayerNode nullPlayer = player(null, "Ghost", null, "support");

        AbiosTeamNode goodTeam = team("t1", "Lynx", List.of(goodPlayer, nullPlayer));
        AbiosTeamNode nullTeam = team(null, "Nameless", List.of(goodPlayer));

        AbiosSeriesNode nullSeries = series(null, "No id", List.of(goodTeam));
        AbiosSeriesNode goodSeries = series("s1", "Series 1", List.of(goodTeam, nullTeam));

        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of(nullSeries, goodSeries));

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.series()).hasSize(1);
        assertThat(snapshot.series().get(0).seriesId()).isEqualTo("s1");
        assertThat(snapshot.teams()).hasSize(1);
        assertThat(snapshot.teams().get(0).teamId()).isEqualTo("t1");
        assertThat(snapshot.players()).hasSize(1);
        assertThat(snapshot.players().get(0).playerId()).isEqualTo("p1");
    }

    @Test
    void resolvesTeamAndPlayersFromRosterEnrichmentWhenSeriesOnlyHasRosterEdges() {
        AbiosTeamNode.Roster edge = new AbiosTeamNode.Roster();
        edge.setId("ros-101");
        AbiosTeamNode slot = new AbiosTeamNode();
        slot.setRoster(edge);

        AbiosSeriesNode s1 = series("ser-1001", "Finals", List.of(slot));

        AbiosTeamNode.TeamStub stub = new AbiosTeamNode.TeamStub();
        stub.setId("team-1");
        stub.setName("Northside Lynx");

        AbiosRosterNode.LineUp lu = new AbiosRosterNode.LineUp();
        lu.setPlayers(List.of(player("pl-501", "Ace", null, "mid")));

        AbiosRosterNode r = new AbiosRosterNode();
        r.setId("ros-101");
        r.setTeam(stub);
        r.setLineUp(lu);

        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of(s1));
        doc.setRosters(List.of(r));

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.teams()).hasSize(1);
        assertThat(snapshot.teams().getFirst().teamId()).isEqualTo("team-1");
        assertThat(snapshot.teams().getFirst().name()).isEqualTo("Northside Lynx");
        assertThat(snapshot.teams().getFirst().playerCount()).isEqualTo(1);
        assertThat(snapshot.players()).hasSize(1);
        assertThat(snapshot.players().getFirst().playerId()).isEqualTo("pl-501");
    }

    @Test
    void usesNestedTeamStubWhenRootTeamIdMissing() {
        AbiosTeamNode.TeamStub stub = new AbiosTeamNode.TeamStub();
        stub.setId("nested-1");
        stub.setName("From Stub");
        AbiosTeamNode slot = new AbiosTeamNode();
        slot.setTeamStub(stub);
        slot.setRoster(new AbiosTeamNode.Roster());
        // Note: Slot might have players list but here we assume it's handled by enrichment if missing
        slot.setPlayers(List.of());

        AbiosSeriesNode s1 = series("s1", "Series 1", List.of(slot));
        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of(s1));

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.series().get(0).teamCount()).isEqualTo(1);
        assertThat(snapshot.teams()).hasSize(1);
        assertThat(snapshot.teams().get(0).teamId()).isEqualTo("nested-1");
        assertThat(snapshot.players()).isEmpty();
    }

    @Test
    void fallsBackToIngameNameAndUnknownNickname() {
        AbiosPlayerNode fromIngame = player("p1", "", "InGameNick", "support");
        AbiosPlayerNode unknown = player("p2", null, null, "support");
        AbiosTeamNode t1 = team("t1", "Lynx", List.of(fromIngame, unknown));
        AbiosSeriesNode s1 = series("s1", "Series 1", List.of(t1));

        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of(s1));

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.players()).extracting("nickname").contains("InGameNick", "Player p2");
    }

    @Test
    void includesAtlasCallsInSnapshot() {
        AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
        doc.setSeries(List.of());
        doc.getAtlasCalls().put("seriesPages", 2);
        doc.getAtlasCalls().put("total", 2);

        LiveSnapshot snapshot = aggregator.aggregate(doc);

        assertThat(snapshot.atlasCalls()).containsEntry("seriesPages", 2);
        assertThat(snapshot.atlasCalls()).containsEntry("total", 2);
    }

    private static AbiosSeriesNode series(String id, String name, List<AbiosTeamNode> teams) {
        AbiosSeriesNode s = new AbiosSeriesNode();
        s.setId(id);
        s.setName(name);
        s.setTeams(teams);
        s.setState("live");
        return s;
    }

    private static AbiosTeamNode team(String id, String name, List<AbiosPlayerNode> players) {
        AbiosTeamNode t = new AbiosTeamNode();
        t.setId(id);
        t.setName(name);
        t.setPlayers(players);
        return t;
    }

    private static AbiosPlayerNode player(String id, String nickname, String ingameName, String role) {
        AbiosPlayerNode p = new AbiosPlayerNode();
        p.setId(id);
        p.setNickname(nickname);
        p.setIngameName(ingameName);
        p.setRole(role);
        return p;
    }
}

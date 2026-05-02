package com.abioscase.live.integration.abios.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class AtlasV3JsonDeserializationTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** Mirrors Atlas V3: participant.teamNode has roster edge object instead of player array */
    @Test
    void seriesArrayWithParticipantRosterEdgeObject() throws Exception {
        String json =
                """
                [{"id":1666229,"participants":[{"seed":1,"roster":{"id":99}}]}]""";

        List<AbiosSeriesNode> list = mapper.readValue(json, new TypeReference<List<AbiosSeriesNode>>() {});

        assertThat(list).hasSize(1);
        assertThat(list.getFirst().allTeams()).hasSize(1);
        assertThat(list.getFirst().allTeams().getFirst().allPlayers()).isEmpty();
        assertThat(list.getFirst().allTeams().getFirst().effectiveTeamId()).isNull();
        assertThat(list.getFirst().allTeams().getFirst().rosterLinkId()).isEqualTo("99");
    }

    @Test
    void participantWithNestedTeamStubAndRosterEdgeStillHasTeamIdentity() throws Exception {
        String json =
                """
                [{"id":1,"participants":[{"team":{"id":"team-1","name":"Lynx"},"roster":{"id":99}}]}]""";

        List<AbiosSeriesNode> list = mapper.readValue(json, new TypeReference<List<AbiosSeriesNode>>() {});

        AbiosTeamNode p = list.getFirst().allTeams().getFirst();
        assertThat(p.effectiveTeamId()).isEqualTo("team-1");
        assertThat(p.effectiveTeamName()).isEqualTo("Lynx");
        assertThat(p.allPlayers()).isEmpty();
    }

    @Test
    void participantWithFlatTeamId() throws Exception {
        String json = """
                [{"id":1,"participants":[{"teamId":42,"name":"Acme"}]}]""";

        List<AbiosSeriesNode> list = mapper.readValue(json, new TypeReference<List<AbiosSeriesNode>>() {});

        assertThat(list.getFirst().allTeams().getFirst().effectiveTeamId()).isEqualTo("42");
    }

    @Test
    void seriesUsesCompetitorsWhenTeamsAndParticipantsAbsent() throws Exception {
        String json =
                """
                [{"id":1,"competitors":[{"id":"c1","name":"Side A","roster":[]}]}]""";

        List<AbiosSeriesNode> list = mapper.readValue(json, new TypeReference<List<AbiosSeriesNode>>() {});

        assertThat(list.getFirst().allTeams()).hasSize(1);
        assertThat(list.getFirst().allTeams().getFirst().effectiveTeamId()).isEqualTo("c1");
    }

    @Test
    void teamWithInlineRosterArray() throws Exception {
        String json =
                """
                [{"id":1,"teams":[{"id":"team-1","roster":[{"id":"p1","nickname":"nk"}]}]}]""";

        List<AbiosSeriesNode> list = mapper.readValue(json, new TypeReference<List<AbiosSeriesNode>>() {});

        assertThat(list.getFirst().allTeams().getFirst().allPlayers()).hasSize(1);
        assertThat(list.getFirst().allTeams().getFirst().allPlayers().getFirst().getId()).isEqualTo("p1");
    }

    @Test
    void atlasListEnvelopeWithDataArrayMapsToTeamList() throws Exception {
        String json = """
                {"data":[{"id":94270,"name":"paiN Academy"}]}""";

        JsonNode root = mapper.readTree(json);
        JsonNode arrayNode = root.isArray() ? root : root.path("data");
        assertThat(arrayNode.isArray()).isTrue();

        List<AbiosTeamNode> teams = mapper.convertValue(arrayNode, new TypeReference<List<AbiosTeamNode>>() {});
        assertThat(teams).hasSize(1);
        assertThat(teams.getFirst().getName()).isEqualTo("paiN Academy");
        assertThat(teams.getFirst().getId()).isEqualTo("94270");
    }
}

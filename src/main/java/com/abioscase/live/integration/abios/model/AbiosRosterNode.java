package com.abioscase.live.integration.abios.model;

import com.abioscase.live.integration.abios.jackson.CoerceToStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbiosRosterNode {
    @JsonProperty("id")
    @JsonDeserialize(using = CoerceToStringDeserializer.class)
    private String id;

    @JsonProperty("team")
    private AbiosTeamNode.TeamStub team;

    @JsonProperty("line_up")
    @JsonAlias("lineup")
    private LineUp lineUp;

    /** Some Atlas payloads (e.g. lineups) attach competitors here instead of under {@link #lineUp}. */
    @JsonProperty("players")
    private List<AbiosPlayerNode> players;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LineUp {
        @JsonProperty("id")
        @JsonDeserialize(using = CoerceToStringDeserializer.class)
        private String id;

        @JsonProperty("players")
        private List<AbiosPlayerNode> players;
    }
}

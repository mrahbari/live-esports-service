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
public class AbiosSeriesNode {
    @JsonProperty("id")
    @JsonDeserialize(using = CoerceToStringDeserializer.class)
    private String id;
    @JsonProperty("name")
    private String name;
    @JsonProperty("title")
    private String title;
    @JsonProperty("gameName")
    private String gameName;

    @JsonProperty("game")
    private Game game;

    public String getGameName() {
        if (gameName != null && !gameName.isBlank()) return gameName;
        return game != null ? game.getName() : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Game {
        @JsonProperty("name")
        private String name;
    }

    @JsonProperty("state")
    @JsonAlias({"lifecycle", "status"})
    private String state;
    @JsonProperty("startedAt")
    @JsonAlias("start")
    private String startedAt;
    @JsonProperty("tier")
    private Integer tier;
    @JsonProperty("best_of")
    @JsonAlias("bestOf")
    private Integer bestOf;
    @JsonProperty("teams")
    private List<AbiosTeamNode> teams;
    @JsonProperty("participants")
    private List<AbiosTeamNode> participants;
    /** Atlas V3 REST sometimes exposes the same slots under {@code competitors}. */
    @JsonProperty("competitors")
    private List<AbiosTeamNode> competitors;

    @JsonProperty("lineups")
    @JsonAlias("line_ups")
    private List<AbiosRosterNode.LineUp> lineups;

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return title != null ? title : "";
    }

    public List<AbiosTeamNode> allTeams() {
        if (teams != null && !teams.isEmpty()) {
            return teams;
        }
        if (participants != null && !participants.isEmpty()) {
            return participants;
        }
        return competitors == null ? List.of() : competitors;
    }
}

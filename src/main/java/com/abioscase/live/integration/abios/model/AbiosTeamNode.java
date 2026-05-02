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
public class AbiosTeamNode {
    @JsonProperty("id")
    @JsonDeserialize(using = CoerceToStringDeserializer.class)
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("abbreviation")
    private String abbreviation;

    @JsonProperty("roster")
    @JsonDeserialize(using = RosterDeserializer.class)
    private Roster roster;

    @JsonProperty("players")
    private List<AbiosPlayerNode> players;

    @JsonProperty("line_up")
    @JsonAlias("lineup")
    private AbiosRosterNode.LineUp lineUp;

    /** Atlas V3 participants often carry the team id here instead of on {@link #id}. */
    @JsonProperty("teamId")
    @JsonDeserialize(using = CoerceToStringDeserializer.class)
    private String teamId;

    /** Nested team summary when participant is a slot wrapping {@code team: { id, name }}. */
    @JsonProperty("team")
    private TeamStub teamStub;

    public List<AbiosPlayerNode> allPlayers() {
        if (roster != null && roster.getPlayers() != null && !roster.getPlayers().isEmpty()) {
            return roster.getPlayers();
        }
        return players == null ? List.of() : players;
    }

    /**
     * Stable team/competitor id when present on the participant slot. Does <strong>not</strong> fall back to
     * {@link Roster#getId()} — that value is a roster resource id, not a team id (using it breaks {@code /teams}
     * batching and yields placeholder names).
     */
    public String effectiveTeamId() {
        if (id != null && !id.isBlank()) return id;
        if (teamId != null && !teamId.isBlank()) return teamId;
        if (teamStub != null && teamStub.getId() != null && !teamStub.getId().isBlank()) return teamStub.getId();
        return null;
    }

    /** Roster edge id from {@link #roster}, when the series payload only references a roster. */
    public String rosterLinkId() {
        return roster != null && roster.getId() != null && !roster.getId().isBlank() ? roster.getId() : null;
    }

    public String effectiveTeamName() {
        if (name != null && !name.isBlank()) return name;
        if (teamStub != null && teamStub.getName() != null && !teamStub.getName().isBlank()) return teamStub.getName();
        if (roster != null && roster.getName() != null && !roster.getName().isBlank()) return roster.getName();
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamStub {
        @JsonProperty("id")
        @JsonDeserialize(using = CoerceToStringDeserializer.class)
        private String id;
        @JsonProperty("name")
        private String name;
        @JsonProperty("abbreviation")
        private String abbreviation;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Roster {
        @JsonProperty("id")
        @JsonDeserialize(using = CoerceToStringDeserializer.class)
        private String id;
        @JsonProperty("name")
        private String name;
        @JsonProperty("abbreviation")
        private String abbreviation;
        @JsonProperty("players")
        private List<AbiosPlayerNode> players;
    }

    public static class RosterDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Roster> {
        @Override
        public Roster deserialize(com.fasterxml.jackson.core.JsonParser p, com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            com.fasterxml.jackson.core.JsonToken t = p.currentToken();
            if (t == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                java.util.List<AbiosPlayerNode> list = ctxt.readValue(p,
                        ctxt.getTypeFactory().constructCollectionType(java.util.List.class, AbiosPlayerNode.class));
                Roster r = new Roster();
                r.setPlayers(list);
                return r;
            } else if (t == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                return ctxt.readValue(p, Roster.class);
            } else if (t != com.fasterxml.jackson.core.JsonToken.VALUE_NULL) {
                p.skipChildren();
            }
            return null;
        }
    }
}

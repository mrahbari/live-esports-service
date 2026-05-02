package com.abioscase.live.integration.abios.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbiosEnrichedDocument {
    @JsonProperty("series")
    private List<AbiosSeriesNode> series;
    @JsonProperty("data")
    private List<AbiosSeriesNode> data;

    /** Enrichment: populated during second hop if needed */
    private List<AbiosRosterNode> rosters;

    private List<AbiosTeamNode> teamsEnriched;
    private List<AbiosPlayerNode> playersEnriched;

    private Map<String, Integer> atlasCalls = new ConcurrentHashMap<>();

    public List<AbiosSeriesNode> allSeries() {
        if (series != null && !series.isEmpty()) {
            return series;
        }
        return data == null ? List.of() : data;
    }
}

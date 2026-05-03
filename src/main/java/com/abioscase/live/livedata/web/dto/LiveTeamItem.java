package com.abioscase.live.livedata.web.dto;

import com.abioscase.live.livedata.web.filter.FieldSelectionAdvice;
import com.fasterxml.jackson.annotation.JsonFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonFilter(FieldSelectionAdvice.FILTER_NAME)
@Schema(description = "A team participating in one or more live series")
public record LiveTeamItem(
        @Schema(description = "Unique team identifier") String teamId,
        @Schema(description = "Full team name") String name,
        @Schema(description = "Short team abbreviation") String abbreviation,
        @Schema(description = "Number of players currently on the roster") int playerCount,
        @Schema(description = "IDs of live series this team participates in") List<String> seriesIds) {}

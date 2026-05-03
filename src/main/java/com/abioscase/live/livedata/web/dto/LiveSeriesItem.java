package com.abioscase.live.livedata.web.dto;

import com.abioscase.live.livedata.web.filter.FieldSelectionAdvice;
import com.fasterxml.jackson.annotation.JsonFilter;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonFilter(FieldSelectionAdvice.FILTER_NAME)
@Schema(description = "A live or ongoing esports series")
public record LiveSeriesItem(
        @Schema(description = "Unique series identifier") String seriesId,
        @Schema(description = "Display name of the series") String name,
        @Schema(description = "Game being played (e.g. Dota 2, CS2)") String gameName,
        @Schema(description = "Series lifecycle state (e.g. live, upcoming)") String state,
        @Schema(description = "ISO-8601 timestamp when the series started") String startedAt,
        @Schema(description = "Competitive tier — lower number means higher prestige") Integer tier,
        @Schema(description = "Best-of format (1, 3, or 5)") Integer bestOf,
        @Schema(description = "Number of identified teams with roster data") int teamCount) {}

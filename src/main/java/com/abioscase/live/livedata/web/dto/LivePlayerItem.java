package com.abioscase.live.livedata.web.dto;

import com.abioscase.live.livedata.web.filter.FieldSelectionAdvice;
import com.fasterxml.jackson.annotation.JsonFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonFilter(FieldSelectionAdvice.FILTER_NAME)
@Schema(description = "A player participating in one or more live series")
public record LivePlayerItem(
        @Schema(description = "Unique player identifier") String playerId,
        @Schema(description = "In-game nickname") String nickname,
        @Schema(description = "Legal first name") String firstName,
        @Schema(description = "Legal last name") String lastName,
        @Schema(description = "In-game role (e.g. carry, support, mid)") String role,
        @Schema(description = "Identifier of the team this player belongs to") String teamId,
        @Schema(description = "Display name of the team") String teamName,
        @Schema(description = "IDs of live series this player appears in") List<String> seriesIds) {}

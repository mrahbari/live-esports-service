package com.abioscase.live.livedata.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated list response with cache metadata")
public record LiveListResponse<T>(
        @Schema(description = "Cache and pagination metadata") LiveMeta meta,
        @Schema(description = "Requested items — fields are filtered when ?fields= is provided") List<T> items) {}

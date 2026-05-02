package com.abioscase.live.livedata.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Cache status and pagination metadata — always returned in full regardless of ?fields=")
public class LiveMeta {
    @Schema(description = "ISO-8601 timestamp of when upstream data was last fetched")
    private String fetchedAt;
    @Schema(description = "True if data is being served from a stale cache entry")
    private boolean stale;
    @Schema(description = "True if upstream is unreachable and very old stale data is being served")
    private boolean degraded;
    @Schema(description = "Number of items in this response page")
    private int count;
    @Schema(description = "Total items available before pagination")
    private int total;
    @Schema(description = "Number of items skipped (offset)")
    private int skip;
    @Schema(description = "Maximum items requested per page")
    private int take;
    @Schema(description = "True if more items exist after this page")
    private boolean hasMore;
    @Schema(description = "Upstream Atlas API call counts made during this refresh cycle")
    private Map<String, Integer> atlasCalls;
}

package com.abioscase.live.livedata.web.dto;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LiveMeta {
    private String fetchedAt;
    private boolean stale;
    private boolean degraded;
    private int count;
    private int total;
    private int skip;
    private int take;
    private boolean hasMore;
    private Map<String, Integer> atlasCalls;
}

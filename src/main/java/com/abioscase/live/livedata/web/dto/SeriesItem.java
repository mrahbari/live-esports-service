package com.abioscase.live.livedata.web.dto;

import java.time.Instant;

public record SeriesItem(
        String id,
        String state,
        Instant startTime,
        Instant updatedAt
) {}

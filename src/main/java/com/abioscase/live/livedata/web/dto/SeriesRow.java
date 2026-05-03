package com.abioscase.live.livedata.web.dto;

import java.time.Instant;

public record SeriesRow(
        String id,
        String state,
        Instant startTime,
        Instant updatedAt
) {}

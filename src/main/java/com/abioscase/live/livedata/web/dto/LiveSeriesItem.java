package com.abioscase.live.livedata.web.dto;

public record LiveSeriesItem(
        String seriesId,
        String name,
        String gameName,
        String state,
        String startedAt,
        Integer tier,
        Integer bestOf,
        int teamCount) {}

package com.abioscase.live.livedata.web.dto;

import java.util.List;

public record LiveTeamItem(
        String teamId,
        String name,
        String abbreviation,
        int playerCount,
        List<String> seriesIds) {}

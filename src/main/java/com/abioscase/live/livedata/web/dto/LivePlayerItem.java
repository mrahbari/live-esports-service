package com.abioscase.live.livedata.web.dto;

import java.util.List;

public record LivePlayerItem(
        String playerId,
        String nickname,
        String firstName,
        String lastName,
        String role,
        String teamId,
        String teamName,
        List<String> seriesIds) {}

package com.abioscase.live.livedata.web.dto;

import java.util.List;

public record LiveListResponse<T>(LiveMeta meta, List<T> items) {}

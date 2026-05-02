package com.abioscase.live.livedata.scheduler;

import com.abioscase.live.livedata.application.LiveDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LiveDataRefreshJob {

    private final LiveDataService liveDataService;

    @Scheduled(fixedDelayString = "${app.live.background-refresh-ms:15000}")
    public void tick() {
        liveDataService.refreshInBackground();
    }
}

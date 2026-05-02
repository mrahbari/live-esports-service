package com.abioscase.live.livedata.web.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abioscase.live.livedata.application.LiveDataService;
import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamTransientException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "app.live.client-rate-limit-per-minute=1",
            "app.live.client-burst-capacity=1"
        })
@AutoConfigureMockMvc
class LiveDataControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LiveDataService liveDataService;

    @Test
    void returnsSeriesResponseWithExpectedMeta() throws Exception {
        LiveSnapshot snapshot = new LiveSnapshot(Instant.parse("2024-01-15T10:30:00Z"), false, false, null, List.of(), List.of(), List.of(), Map.of("total", 5));
        when(liveDataService.getSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/series/live").header("X-Forwarded-For", "10.0.0.10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.fetchedAt").value("2024-01-15T10:30:00Z"))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.degraded").value(false))
                .andExpect(jsonPath("$.meta.count").value(0))
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.skip").value(0))
                .andExpect(jsonPath("$.meta.hasMore").value(false))
                .andExpect(jsonPath("$.meta.atlasCalls.total").value(5))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void returns429WithRetryAfterWhenIpRateLimited() throws Exception {
        LiveSnapshot snapshot = new LiveSnapshot(Instant.now(), false, false, null, List.of(), List.of(), List.of(), Map.of());
        when(liveDataService.getSnapshot()).thenReturn(snapshot);

        String ip = "10.0.0.20";
        mockMvc.perform(get("/series/live").header("X-Forwarded-For", ip)).andExpect(status().isOk());
        mockMvc.perform(get("/series/live").header("X-Forwarded-For", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void returns503OnTransientUpstreamFailure() throws Exception {
        when(liveDataService.getSnapshot()).thenThrow(new AbiosUpstreamTransientException("temporary issue"));

        mockMvc.perform(get("/series/live").header("X-Forwarded-For", "10.0.0.30"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Upstream Temporary Failure"));
    }
}

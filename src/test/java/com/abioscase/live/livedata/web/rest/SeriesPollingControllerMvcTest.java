package com.abioscase.live.livedata.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abioscase.live.livedata.db.service.DbLiveDataService;
import com.abioscase.live.livedata.polling.PollingSeriesRepository;
import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import com.abioscase.live.livedata.web.dto.SeriesItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SeriesPollingControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DbLiveDataService dbService;

    @MockBean
    private PollingSeriesRepository pollingRepo;

    // ── GET /v2/series/live ──────────────────────────────────────────────────────

    @Test
    void liveSeries_returnsEnrichedItemsWithCursor() throws Exception {
        var series = List.of(
                new LiveSeriesItem("s-1", "ESL Pro League", "CS2", "live", "2026-05-03T10:00:00Z", 1, 3, 2),
                new LiveSeriesItem("s-2", "DreamHack Open",  "Dota 2", "ongoing", "2026-05-03T11:00:00Z", 2, 1, 1)
        );
        when(dbService.getLiveSeries(isNull(), eq(50)))
                .thenReturn(new DbLiveDataService.PageResult<>(series, "s-2", 2));

        mockMvc.perform(get("/v2/series/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].seriesId").value("s-1"))
                .andExpect(jsonPath("$.items[0].name").value("ESL Pro League"))
                .andExpect(jsonPath("$.items[1].seriesId").value("s-2"))
                .andExpect(jsonPath("$.nextCursor").value("s-2"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void liveSeries_emptyResult_returnsEmptyPage() throws Exception {
        when(dbService.getLiveSeries(isNull(), anyInt()))
                .thenReturn(new DbLiveDataService.PageResult<>(List.of(), null, 0));

        mockMvc.perform(get("/v2/series/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void liveSeries_withCursorParam_forwardsCursorToService() throws Exception {
        when(dbService.getLiveSeries(eq("s-5"), eq(10)))
                .thenReturn(new DbLiveDataService.PageResult<>(List.of(), null, 0));

        mockMvc.perform(get("/v2/series/live").param("cursor", "s-5").param("take", "10"))
                .andExpect(status().isOk());
    }

    // ── GET /v2/series/upcoming ──────────────────────────────────────────────────

    @Test
    void upcomingSeries_returnsRowsFromPollingRepo() throws Exception {
        Instant start   = Instant.parse("2026-05-10T14:00:00Z");
        Instant updated = Instant.parse("2026-05-03T12:00:00Z");
        var rows = List.of(
                new SeriesItem("101", "upcoming", start, updated),
                new SeriesItem("202", "upcoming", start.plusSeconds(3600), updated)
        );
        when(pollingRepo.getByState(eq("upcoming"), isNull(), eq(50)))
                .thenReturn(new PollingSeriesRepository.PageResult(rows, "202", true));

        mockMvc.perform(get("/v2/series/upcoming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("101"))
                .andExpect(jsonPath("$.items[0].state").value("upcoming"))
                .andExpect(jsonPath("$.nextCursor").value("202"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void upcomingSeries_withCursorParam_forwardsCursorToRepo() throws Exception {
        when(pollingRepo.getByState(eq("upcoming"), eq("50"), eq(25)))
                .thenReturn(new PollingSeriesRepository.PageResult(List.of(), null, false));

        mockMvc.perform(get("/v2/series/upcoming").param("cursor", "50").param("take", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ── GET /v2/teams/live ────────────────────────────────────────────────────────

    @Test
    void liveTeams_returnsEnrichedTeams() throws Exception {
        var teams = List.of(
                new LiveTeamItem("t-1", "Team Liquid", "TL", 5, List.of("s-1")),
                new LiveTeamItem("t-2", "Natus Vincere", "NAVI", 5, List.of("s-1"))
        );
        when(dbService.getLiveTeams(isNull(), eq(50)))
                .thenReturn(new DbLiveDataService.PageResult<>(teams, null, 2));

        mockMvc.perform(get("/v2/teams/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].teamId").value("t-1"))
                .andExpect(jsonPath("$.items[0].name").value("Team Liquid"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    // ── GET /v2/players/live ──────────────────────────────────────────────────────

    @Test
    void livePlayers_returnsEnrichedPlayers() throws Exception {
        var players = List.of(
                new LivePlayerItem("p-1", "s1mple", "Oleksandr", "Kostyliev", "rifler", "t-2", "Natus Vincere", List.of("s-1"))
        );
        when(dbService.getLivePlayers(isNull(), eq(50)))
                .thenReturn(new DbLiveDataService.PageResult<>(players, null, 1));

        mockMvc.perform(get("/v2/players/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].playerId").value("p-1"))
                .andExpect(jsonPath("$.items[0].nickname").value("s1mple"))
                .andExpect(jsonPath("$.items[0].teamName").value("Natus Vincere"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }
}

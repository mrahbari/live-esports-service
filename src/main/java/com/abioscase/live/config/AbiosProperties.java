package com.abioscase.live.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "abios")
public class AbiosProperties {

    private boolean v1MockEnabled = true;
    @NotBlank
    private String baseUrl = "https://api.abiosgaming.com";
    @NotBlank
    private String seriesPath = "/v3/series";
    private String seriesQuery = "filter=lifecycle=live";

    private String rostersPath = "/v3/rosters";
    private String rostersQuery = "include=team&include=line_up.players.player";

    private String lineupsPath = "/v3/lineups";
    private String lineupsQuery = "include=players";

    private String teamsPath = "/v3/teams";
    private String teamsQuery = "";

    private String playersPath = "/v3/players";
    private String playersQuery = "";

    @Positive
    private int connectTimeoutMs = 3_000;
    @Positive
    private int readTimeoutMs = 8_000;
    @NotBlank
    private String authHeaderName = "Authorization";
    @NotBlank
    private String authHeaderFormat = "Bearer {token}";
    private String apiKey = "";
    @Positive
    private int batchSize = 50;
    @Positive
    private int maxSeriesPages = 25;
    @Min(0)
    private long minIntervalMs = 0;
    private boolean logUpstreamPayload = false;

    /** Query string used by LiveSeriesWorker when polling live series. */
    private String livePollingQuery = "filter=lifecycle<=live";
    /** Query string used by UpcomingSeriesWorker when polling upcoming series. */
    private String upcomingPollingQuery = "filter=lifecycle<=upcoming&ordering=start-asc";

    @AssertTrue(message = "ABIOS_API_KEY (abios.api-key) must not be blank when abios.v1-mock-enabled=false")
    public boolean isApiKeyPresentForLiveMode() {
        return v1MockEnabled || (apiKey != null && !apiKey.isBlank());
    }
}

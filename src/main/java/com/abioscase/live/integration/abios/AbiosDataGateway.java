package com.abioscase.live.integration.abios;

import com.abioscase.live.config.AbiosProperties;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamAuthException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamClientException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamEndpointException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamRateLimitedException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamTransientException;
import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.abioscase.live.shared.util.HttpUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Unprotected HTTP and classpath mock — Resilience4j decorators live in {@link ResilientAbiosFetch} only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbiosDataGateway {

    private final AbiosProperties abios;
    private final RestClient abiosRestClient;
    private final MeterRegistry meterRegistry;
    private final AbiosMockReader mockReader;
    private final AbiosResponseParser responseParser;

    public List<AbiosRosterNode> readMockRosters() {
        return mockReader.readMockRosters();
    }

    public AbiosEnrichedDocument readMock() {
        return mockReader.readMock();
    }

    public AbiosEnrichedDocument fetchSeries(int take, int skip) {
        log.info("Abios upstream request: GET {}?{} (take={}, skip={})", abios.getSeriesPath(), abios.getSeriesQuery(), take, skip);

        URI uri = UriComponentsBuilder.fromPath(abios.getSeriesPath())
                .query(abios.getSeriesQuery())
                .queryParam("take", take)
                .queryParam("skip", skip)
                .build()
                .toUri();

        byte[] payload = abiosRestClient
                .get()
                .uri(uri)
                .headers(this::setAuth)
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        (req, res) -> {
                            HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                            String body = HttpUtils.readBodySafely(res.getBody());
                            meterRegistry.counter("upstream.error.status", "code", String.valueOf(res.getStatusCode().value())).increment();
                            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                                throw new AbiosUpstreamAuthException(
                                        "Abios auth/config error: HTTP " + res.getStatusCode() + " body=" + body);
                            }
                            if (status == HttpStatus.NOT_FOUND) {
                                throw new AbiosUpstreamEndpointException(
                                        "Abios endpoint error: HTTP " + res.getStatusCode() + " body=" + body);
                            }
                            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                                long retryAfter = HttpUtils.parseRetryAfterSeconds(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                                throw new AbiosUpstreamRateLimitedException(
                                        "Abios rate limited: HTTP " + res.getStatusCode() + " body=" + body, retryAfter);
                            }
                            throw new AbiosUpstreamClientException(
                                    "Abios client error: HTTP " + res.getStatusCode() + " body=" + body);
                        })
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        (req, res) -> {
                            meterRegistry.counter("upstream.error.status", "code", String.valueOf(res.getStatusCode().value())).increment();
                            throw new AbiosUpstreamTransientException("Abios server error: " + res.getStatusCode());
                        })
                .body(byte[].class);

        responseParser.logRawUpstreamResponse(payload);
        try {
            return responseParser.parseSeriesPayload(payload);
        } catch (IOException e) {
            throw new AbiosUpstreamTransientException("Failed to parse Abios series JSON: " + e.getMessage(), e);
        }
    }

    public List<AbiosRosterNode> fetchRosters(Set<String> ids) {
        return fetchBatch(abios.getRostersPath(), ids, abios.getRostersQuery(), new TypeReference<List<AbiosRosterNode>>() {});
    }

    public List<AbiosTeamNode> fetchTeams(Set<String> ids) {
        return fetchBatch(abios.getTeamsPath(), ids, abios.getTeamsQuery(), new TypeReference<List<AbiosTeamNode>>() {});
    }

    public List<AbiosPlayerNode> fetchPlayers(Set<String> ids) {
        return fetchBatch(abios.getPlayersPath(), ids, abios.getPlayersQuery(), new TypeReference<List<AbiosPlayerNode>>() {});
    }

    public List<AbiosRosterNode> fetchLineups(Set<String> ids) {
        return fetchBatch(abios.getLineupsPath(), ids, abios.getLineupsQuery(), new TypeReference<List<AbiosRosterNode>>() {});
    }

    private <T> List<T> fetchBatch(String path, Set<String> ids, String extraQuery, TypeReference<List<T>> typeRef) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        String idList = String.join(",", ids);
        String encodedFilter = "id%3C%3D%7B" + idList + "%7D";

        StringBuilder urlBuilder = new StringBuilder(abios.getBaseUrl())
                .append(path)
                .append("?filter=").append(encodedFilter)
                .append("&take=").append(ids.size());
        if (extraQuery != null && !extraQuery.isBlank()) {
            urlBuilder.append('&').append(extraQuery);
        }

        String fullUrl = urlBuilder.toString();
        log.info("Abios upstream batch request ({} IDs): {}", ids.size(), fullUrl);

        byte[] payload = abiosRestClient.get()
                .uri(URI.create(fullUrl))
                .headers(this::setAuth)
                .retrieve()
                .onStatus(
                        s -> s.value() == HttpStatus.TOO_MANY_REQUESTS.value(),
                        (req, res) -> {
                            long retryAfter = HttpUtils.parseRetryAfterSeconds(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                            meterRegistry.counter("upstream.error.status", "code", "429").increment();
                            throw new AbiosUpstreamRateLimitedException("Abios rate limited on " + path, retryAfter);
                        })
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        (req, res) -> {
                            meterRegistry.counter("upstream.error.status", "code", String.valueOf(res.getStatusCode().value())).increment();
                            throw new AbiosUpstreamTransientException("Abios server error on " + path + ": " + res.getStatusCode());
                        })
                .onStatus(
                        s -> s.value() == 401 || s.value() == 403,
                        (req, res) -> {
                            throw new AbiosUpstreamAuthException("Auth error on " + path + ": " + res.getStatusCode());
                        })
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        (req, res) -> log.warn("Batch fetch from {} non-retryable client error: {}", path, res.getStatusCode()))
                .body(byte[].class);

        responseParser.logRawUpstreamResponse(payload);
        try {
            return responseParser.parseAtlasListPayload(payload, path, typeRef);
        } catch (IOException e) {
            throw new AbiosUpstreamTransientException("Failed to parse batch response from " + path + ": " + e.getMessage(), e);
        }
    }

    private void setAuth(HttpHeaders headers) {
        if (abios.getApiKey() == null || abios.getApiKey().isBlank()) {
            return;
        }
        String value = abios.getAuthHeaderFormat().replace("{token}", abios.getApiKey());
        headers.set(abios.getAuthHeaderName(), value);
    }
}

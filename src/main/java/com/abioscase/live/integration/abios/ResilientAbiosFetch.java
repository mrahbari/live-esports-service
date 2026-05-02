package com.abioscase.live.integration.abios;

import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Token-bucket and retry against upstream — does not see mock; {@link AbiosSnapshotReader} routes that.
 */
@Component
@RequiredArgsConstructor
public class ResilientAbiosFetch {

    private final AbiosDataGateway gateway;

    @Retry(name = "abios")
    @RateLimiter(name = "abiosOut")
    @CircuitBreaker(name = "abios")
    public AbiosEnrichedDocument fetchSeries(int take, int skip) {
        return gateway.fetchSeries(take, skip);
    }

    @Retry(name = "abios")
    @RateLimiter(name = "abiosOut")
    @CircuitBreaker(name = "abios")
    public List<AbiosRosterNode> fetchRosters(Set<String> ids) {
        return gateway.fetchRosters(ids);
    }

    @Retry(name = "abios")
    @RateLimiter(name = "abiosOut")
    @CircuitBreaker(name = "abios")
    public List<AbiosTeamNode> fetchTeams(Set<String> ids) {
        return gateway.fetchTeams(ids);
    }

    @Retry(name = "abios")
    @RateLimiter(name = "abiosOut")
    @CircuitBreaker(name = "abios")
    public List<AbiosPlayerNode> fetchPlayers(Set<String> ids) {
        return gateway.fetchPlayers(ids);
    }

    @Retry(name = "abios")
    @RateLimiter(name = "abiosOut")
    @CircuitBreaker(name = "abios")
    public List<AbiosRosterNode> fetchLineups(Set<String> ids) {
        return gateway.fetchLineups(ids);
    }
}

package com.abioscase.live.livedata.web.filter;

import com.abioscase.live.shared.exception.RateLimitExceededException;
import com.abioscase.live.config.LiveDataProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP token bucket limiter for public endpoints.
 * Supports both in-memory (Caffeine) and Redis-based (distributed) limiting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_IPS = 20_000;
    private final Cache<String, Bucket> localBuckets;
    private final LiveDataProperties props;
    private final MeterRegistry meterRegistry;
    private final HandlerExceptionResolver exceptionResolver;
    private final ProxyManager<byte[]> proxyManager;

    public IpRateLimitFilter(
            LiveDataProperties props,
            MeterRegistry meterRegistry,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @Autowired(required = false) ProxyManager<byte[]> proxyManager) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.exceptionResolver = exceptionResolver;
        this.proxyManager = proxyManager;
        this.localBuckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IPS)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Limit only public live data endpoints (v1 and non-v1, with and without /live suffix)
        boolean isSeries = path.equals("/v1/series") || path.equals("/series/live") || path.equals("/v1/series/live");
        boolean isPlayers = path.equals("/v1/players") || path.equals("/players/live") || path.equals("/v1/players/live");
        boolean isTeams = path.equals("/v1/teams") || path.equals("/teams/live") || path.equals("/v1/teams/live");
        
        return !(isSeries || isPlayers || isTeams);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        Bucket bucket = resolveBucket(clientIp);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = nanosToSecondsCeil(probe.getNanosToWaitForRefill());
            meterRegistry.counter("client.rate_limited").increment();
            exceptionResolver.resolveException(
                    request, response, null, new RateLimitExceededException(retryAfter));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Bucket resolveBucket(String clientIp) {
        if ("redis".equalsIgnoreCase(props.getCacheMode()) && proxyManager != null) {
            String key = "rl:ip:" + clientIp;
            return proxyManager.builder().build(key.getBytes(), this::newLimitConfiguration);
        }

        return localBuckets.get(clientIp, key -> Bucket.builder().addLimit(newLimit()).build());
    }

    private io.github.bucket4j.BucketConfiguration newLimitConfiguration() {
        return io.github.bucket4j.BucketConfiguration.builder()
                .addLimit(newLimit())
                .build();
    }

    private Bandwidth newLimit() {
        return Bandwidth.builder()
                .capacity(props.getClientBurstCapacity())
                .refillIntervally(props.getClientRateLimitPerMinute(), java.time.Duration.ofMinutes(1))
                .build();
    }

    private static long nanosToSecondsCeil(long nanos) {
        if (nanos <= 0) {
            return 1L;
        }
        long seconds = nanos / 1_000_000_000L;
        return (nanos % 1_000_000_000L == 0) ? Math.max(1L, seconds) : seconds + 1L;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        return Objects.toString(request.getRemoteAddr(), "unknown");
    }

}

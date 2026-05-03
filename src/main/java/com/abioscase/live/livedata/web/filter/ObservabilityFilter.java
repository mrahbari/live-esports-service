package com.abioscase.live.livedata.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class ObservabilityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        MDC.put("endpoint", path);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latency = System.currentTimeMillis() - start;
            MDC.put("latencyMs", String.valueOf(latency));
            // Log completion for structured logs to pick up
            if (!path.startsWith("/actuator")) {
                log.info("Request completed: {} {} - {}ms", request.getMethod(), path, latency);
            }
            MDC.remove("endpoint");
            MDC.remove("latencyMs");
            MDC.remove("cacheStatus");
            MDC.remove("upstreamStatus");
        }
    }
}

package com.example.urlshortner2.ratelimit;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.urlshortner2.auth.ApiKeyFilter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Token bucket per API key on link creation ({@code POST /api/links}), the abusable write path.
 * Runs after {@link ApiKeyFilter}. Fails <b>open</b> if Redis is unavailable: briefly allowing extra
 * link creation is better than blocking all of it (a payment system would fail closed).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TokenBucketLimiter limiter;
    private final Counter rejected;
    private final Counter errors;

    public RateLimitFilter(TokenBucketLimiter limiter, MeterRegistry meters) {
        this.limiter = limiter;
        this.rejected = meters.counter("ratelimit.rejected");
        this.errors = meters.counter("ratelimit.errors");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/api/links".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Object keyId = req.getAttribute(ApiKeyFilter.KEY_ID_ATTR);
        if (keyId == null) { // ApiKeyFilter already rejected unauthenticated requests
            chain.doFilter(req, res);
            return;
        }
        TokenBucketLimiter.Decision d;
        try {
            d = limiter.tryConsume(keyId.toString());
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Rate limiter unavailable, failing open: {}", e.toString());
            chain.doFilter(req, res);
            return;
        }
        res.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            rejected.increment();
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}

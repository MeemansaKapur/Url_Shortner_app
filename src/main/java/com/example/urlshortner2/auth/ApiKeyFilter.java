package com.example.urlshortner2.auth;

import java.io.IOException;
import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Requires a valid {@code X-API-Key} on every {@code /api/**} request. Redirects are public. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String KEY_ID_ATTR = "apiKeyId";

    private final ApiKeyService keys;

    public ApiKeyFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> keyId = keys.authenticate(req.getHeader(HEADER));
        if (keyId.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"missing or invalid X-API-Key\"}");
            return;
        }
        req.setAttribute(KEY_ID_ATTR, keyId.get());
        chain.doFilter(req, res);
    }
}

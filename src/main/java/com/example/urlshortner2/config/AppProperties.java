package com.example.urlshortner2.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Storage storage,
        int codeLength,
        String adminToken,
        Cache cache,
        RateLimit rateLimit,
        Mongo mongo,
        Postgres postgres) {

    public enum Storage { POSTGRES, MONGO }

    public record Cache(boolean enabled, Duration maxTtl, Duration negativeTtl) {}

    public record RateLimit(int capacity, double refillPerSecond) {}

    public record Mongo(String uri, String database, int maxPoolSize) {}

    public record Postgres(String url, String username, String password, int maxPoolSize) {}
}

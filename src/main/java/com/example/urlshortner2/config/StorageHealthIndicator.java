package com.example.urlshortner2.config;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/** Reports the active storage backend in /actuator/health (as component "storage"). */
public class StorageHealthIndicator extends AbstractHealthIndicator {

    private final String backend;
    private final Runnable ping;

    public StorageHealthIndicator(String backend, Runnable ping) {
        this.backend = backend;
        this.ping = ping;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        ping.run();
        builder.up().withDetail("backend", backend);
    }
}

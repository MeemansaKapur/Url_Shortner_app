package com.example.urlshortner2.storage.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Postgres has no TTL index, so expired links are deleted every minute in batches of 1,000. */
public class ExpiryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupJob.class);
    static final int BATCH = 1_000;

    private final PostgresLinkRepository repo;

    public ExpiryCleanupJob(PostgresLinkRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void run() {
        int total = 0;
        int deleted;
        do {
            deleted = repo.deleteExpiredBatch(BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Expiry cleanup deleted {} links", total);
        }
    }
}

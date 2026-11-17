package com.example.urlshortner2.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.urlshortner2.link.Link;

/**
 * Storage port for links. Implemented by MongoDB and PostgreSQL; the active one is chosen
 * by {@code app.storage}. Both implementations pass the same contract test suite.
 */
public interface LinkRepository {

    /**
     * Inserts the link. Uniqueness is enforced by the database's unique key (atomic),
     * never by a check-then-insert.
     *
     * @return false if the code already exists (caller retries with a new code)
     */
    boolean insertIfAbsent(Link link);

    /** Finds a link by code. Expired links are treated as absent, regardless of background cleanup timing. */
    Optional<Link> findByCode(String code, Instant now);

    /** Deletes the link only if it belongs to {@code ownerKeyId}. */
    boolean delete(String code, String ownerKeyId);

    /** Bulk insert used for seeding load-test data. Returns the codes actually inserted. */
    List<String> insertBatch(List<Link> links);
}

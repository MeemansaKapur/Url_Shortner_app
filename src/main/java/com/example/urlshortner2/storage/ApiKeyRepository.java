package com.example.urlshortner2.storage;

import java.time.Instant;
import java.util.Optional;

public interface ApiKeyRepository {

    record ApiKeyRecord(String keyId, byte[] secretHash, String name, Instant createdAt, boolean active) {}

    /** @return false if a key with the same name already exists */
    boolean insert(ApiKeyRecord key);

    Optional<ApiKeyRecord> findById(String keyId);
}

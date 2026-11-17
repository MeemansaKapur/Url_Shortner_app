package com.example.urlshortner2.cache;

import java.time.Duration;

/** Minimal key-value cache port (Redis in production, an in-memory fake in tests). */
public interface CacheStore {

    String get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);
}

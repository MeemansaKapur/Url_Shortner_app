package com.example.urlshortner2.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.storage.LinkRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Resolves a short code to its long URL.
 * <ul>
 *   <li><b>Cache-aside</b>: read cache, on miss read the DB and fill the cache.</li>
 *   <li><b>Cache TTL ≤ remaining link lifetime</b> (capped at 24 h), so an expired link is never served from cache.</li>
 *   <li><b>Negative caching</b> (60 s) of unknown codes, so bots and typos don't all reach the DB.</li>
 *   <li><b>Request coalescing</b>: concurrent misses for the same code share one DB load (stampede protection).</li>
 *   <li><b>Graceful degradation</b>: if the cache errors, go to the DB and skip the cache for 1 s.</li>
 * </ul>
 * With {@code app.cache.enabled=false} every request goes straight to the DB: the uncached baseline for load tests.
 */
public class CachedLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(CachedLinkResolver.class);

    static final String PREFIX = "l:";
    static final String NOT_FOUND = "__404__";
    private static final long BACKOFF_NANOS = Duration.ofSeconds(1).toNanos();

    private final LinkRepository repo;
    private final CacheStore cache;
    private final boolean enabled;
    private final Duration maxTtl;
    private final Duration negativeTtl;
    private final Clock clock;

    private final ConcurrentHashMap<String, CompletableFuture<Optional<String>>> inFlight = new ConcurrentHashMap<>();
    private volatile long cacheBypassUntil = 0L;

    private final Counter hits;
    private final Counter negativeHits;
    private final Counter misses;
    private final Counter errors;
    private final Counter coalesced;

    public CachedLinkResolver(LinkRepository repo, CacheStore cache, boolean enabled,
                              Duration maxTtl, Duration negativeTtl, Clock clock, MeterRegistry meters) {
        this.repo = repo;
        this.cache = cache;
        this.enabled = enabled;
        this.maxTtl = maxTtl;
        this.negativeTtl = negativeTtl;
        this.clock = clock;
        this.hits = meters.counter("cache.hits");
        this.negativeHits = meters.counter("cache.negative.hits");
        this.misses = meters.counter("cache.misses");
        this.errors = meters.counter("cache.errors");
        this.coalesced = meters.counter("cache.coalesced");
    }

    public Optional<String> resolve(String code) {
        if (!enabled) {
            return repo.findByCode(code, clock.instant()).map(Link::url);
        }
        if (cacheUsable()) {
            try {
                String v = cache.get(PREFIX + code);
                if (NOT_FOUND.equals(v)) {
                    negativeHits.increment();
                    return Optional.empty();
                }
                if (v != null) {
                    hits.increment();
                    return Optional.of(v);
                }
            } catch (RuntimeException e) {
                cacheFailed(e);
            }
        }
        misses.increment();
        return loadCoalesced(code);
    }

    /** Called after delete (and after create, to drop any negative entry for the code). */
    public void evict(String code) {
        if (!enabled || !cacheUsable()) {
            return;
        }
        try {
            cache.delete(PREFIX + code);
        } catch (RuntimeException e) {
            cacheFailed(e);
        }
    }

    Optional<String> loadCoalesced(String code) {
        CompletableFuture<Optional<String>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<String>> existing = inFlight.putIfAbsent(code, mine);
        if (existing != null) {
            coalesced.increment();
            try {
                return existing.join(); // parks the virtual thread cheaply
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        try {
            Optional<String> result = loadFromDbAndCache(code);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(code, mine);
        }
    }

    private Optional<String> loadFromDbAndCache(String code) {
        Instant now = clock.instant();
        Optional<Link> link = repo.findByCode(code, now);
        if (!cacheUsable()) {
            return link.map(Link::url);
        }
        try {
            if (link.isEmpty()) {
                cache.set(PREFIX + code, NOT_FOUND, negativeTtl);
            } else {
                Duration ttl = cacheTtl(link.get(), now, maxTtl);
                if (ttl.toMillis() > 0) {
                    cache.set(PREFIX + code, link.get().url(), ttl);
                }
            }
        } catch (RuntimeException e) {
            cacheFailed(e);
        }
        return link.map(Link::url);
    }

    /** Never cache past the link's own expiry; cap at {@code maxTtl} to bound memory. */
    static Duration cacheTtl(Link link, Instant now, Duration maxTtl) {
        if (link.expiresAt() == null) {
            return maxTtl;
        }
        Duration remaining = Duration.between(now, link.expiresAt());
        if (remaining.isNegative()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(maxTtl) < 0 ? remaining : maxTtl;
    }

    private boolean cacheUsable() {
        return System.nanoTime() - cacheBypassUntil >= 0;
    }

    private void cacheFailed(RuntimeException e) {
        errors.increment();
        cacheBypassUntil = System.nanoTime() + BACKOFF_NANOS;
        log.warn("Cache unavailable, using database directly for 1s: {}", e.toString());
    }
}

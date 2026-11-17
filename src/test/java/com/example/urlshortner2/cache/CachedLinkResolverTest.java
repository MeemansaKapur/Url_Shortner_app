package com.example.urlshortner2.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.storage.LinkRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CachedLinkResolverTest {

    static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Duration MAX = Duration.ofHours(24);
    static final Duration NEG = Duration.ofSeconds(60);

    /** Counts DB reads; optionally slow, to widen the race window for coalescing. */
    static class CountingRepo implements LinkRepository {
        final Map<String, Link> data = new ConcurrentHashMap<>();
        final AtomicInteger finds = new AtomicInteger();
        volatile long delayMs = 0;

        @Override public boolean insertIfAbsent(Link l) { return data.putIfAbsent(l.code(), l) == null; }
        @Override public boolean delete(String c, String o) { return data.remove(c) != null; }
        @Override public List<String> insertBatch(List<Link> links) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<Link> findByCode(String code, Instant now) {
            finds.incrementAndGet();
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.ofNullable(data.get(code)).filter(l -> !l.isExpiredAt(now));
        }
    }

    static class MapCache implements CacheStore {
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Duration> ttls = new ConcurrentHashMap<>();
        @Override public String get(String k) { return values.get(k); }
        @Override public void set(String k, String v, Duration ttl) { values.put(k, v); ttls.put(k, ttl); }
        @Override public void delete(String k) { values.remove(k); ttls.remove(k); }
    }

    static class BrokenCache implements CacheStore {
        @Override public String get(String k) { throw new IllegalStateException("redis down"); }
        @Override public void set(String k, String v, Duration ttl) { throw new IllegalStateException("redis down"); }
        @Override public void delete(String k) { throw new IllegalStateException("redis down"); }
    }

    private static CachedLinkResolver resolver(LinkRepository repo, CacheStore cache, boolean enabled) {
        return new CachedLinkResolver(repo, cache, enabled, MAX, NEG, CLOCK, new SimpleMeterRegistry());
    }

    private static Link link(String code, Instant expiresAt) {
        return new Link(code, "https://example.com/" + code, NOW, expiresAt, "k");
    }

    @Test
    void secondReadIsServedFromCache() {
        CountingRepo repo = new CountingRepo();
        repo.insertIfAbsent(link("abc1234", null));
        CachedLinkResolver r = resolver(repo, new MapCache(), true);

        assertThat(r.resolve("abc1234")).contains("https://example.com/abc1234");
        assertThat(r.resolve("abc1234")).contains("https://example.com/abc1234");
        assertThat(repo.finds.get()).isEqualTo(1);
    }

    @Test
    void unknownCodesAreNegativelyCached() {
        CountingRepo repo = new CountingRepo();
        MapCache cache = new MapCache();
        CachedLinkResolver r = resolver(repo, cache, true);

        assertThat(r.resolve("nope123")).isEmpty();
        assertThat(r.resolve("nope123")).isEmpty();
        assertThat(repo.finds.get()).isEqualTo(1);
        assertThat(cache.ttls.get("l:nope123")).isEqualTo(NEG);
    }

    @Test
    void cacheTtlNeverExceedsLinkExpiry() {
        CountingRepo repo = new CountingRepo();
        MapCache cache = new MapCache();
        repo.insertIfAbsent(link("soon123", NOW.plusSeconds(90)));
        repo.insertIfAbsent(link("later12", NOW.plus(Duration.ofDays(10))));
        repo.insertIfAbsent(link("perm123", null));
        CachedLinkResolver r = resolver(repo, cache, true);

        r.resolve("soon123");
        r.resolve("later12");
        r.resolve("perm123");

        assertThat(cache.ttls.get("l:soon123")).isEqualTo(Duration.ofSeconds(90));
        assertThat(cache.ttls.get("l:later12")).isEqualTo(MAX);
        assertThat(cache.ttls.get("l:perm123")).isEqualTo(MAX);
    }

    @Test
    void cacheTtlIsZeroForAlreadyExpiredLink() {
        assertThat(CachedLinkResolver.cacheTtl(link("x", NOW.minusSeconds(1)), NOW, MAX)).isEqualTo(Duration.ZERO);
    }

    @Test
    void concurrentMissesForSameCodeHitDatabaseOnce() throws InterruptedException {
        CountingRepo repo = new CountingRepo();
        repo.insertIfAbsent(link("viral12", null));
        repo.delayMs = 200;
        CachedLinkResolver r = resolver(repo, new MapCache(), true);

        ConcurrentLinkedQueue<Optional<String>> results = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                exec.submit(() -> {
                    start.await();
                    results.add(r.resolve("viral12"));
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(repo.finds.get()).isEqualTo(1);
        assertThat(results).hasSize(100).allMatch(o -> o.equals(Optional.of("https://example.com/viral12")));
    }

    @Test
    void cacheFailureFallsBackToDatabase() {
        CountingRepo repo = new CountingRepo();
        repo.insertIfAbsent(link("abc1234", null));
        CachedLinkResolver r = resolver(repo, new BrokenCache(), true);

        assertThat(r.resolve("abc1234")).contains("https://example.com/abc1234");
        assertThat(r.resolve("abc1234")).contains("https://example.com/abc1234");
        assertThat(repo.finds.get()).isEqualTo(2);
    }

    @Test
    void disabledCacheAlwaysReadsDatabase() {
        CountingRepo repo = new CountingRepo();
        MapCache cache = new MapCache();
        repo.insertIfAbsent(link("abc1234", null));
        CachedLinkResolver r = resolver(repo, cache, false);

        r.resolve("abc1234");
        r.resolve("abc1234");
        assertThat(repo.finds.get()).isEqualTo(2);
        assertThat(cache.values).isEmpty();
    }

    @Test
    void evictRemovesEntry() {
        CountingRepo repo = new CountingRepo();
        MapCache cache = new MapCache();
        repo.insertIfAbsent(link("abc1234", null));
        CachedLinkResolver r = resolver(repo, cache, true);

        r.resolve("abc1234");
        repo.delete("abc1234", "k");
        r.evict("abc1234");
        assertThat(r.resolve("abc1234")).isEmpty();
    }
}

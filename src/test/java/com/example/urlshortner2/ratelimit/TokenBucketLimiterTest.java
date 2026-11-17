package com.example.urlshortner2.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.urlshortner2.config.AppProperties;

@Testcontainers
class TokenBucketLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static TokenBucketLimiter limiter;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        AppProperties props = new AppProperties(null, 7, null, null, new AppProperties.RateLimit(20, 5), null, null);
        limiter = new TokenBucketLimiter(redis, props);
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) {
            TokenBucketLimiter.Decision d = limiter.tryConsume(key);
            assertThat(d.allowed()).as("request %d", i + 1).isTrue();
            assertThat(d.remaining()).isEqualTo(19 - i);
        }
        TokenBucketLimiter.Decision rejected = limiter.tryConsume(key);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
        assertThat(rejected.limit()).isEqualTo(20);
    }

    @Test
    void refillsAtConfiguredRate() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) {
            limiter.tryConsume(key);
        }
        Thread.sleep(1_050);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryConsume(key).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(5, 6);
    }

    @Test
    void bucketsAreIndependentPerKey() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) {
            limiter.tryConsume(a);
        }
        assertThat(limiter.tryConsume(a).allowed()).isFalse();
        assertThat(limiter.tryConsume(b).allowed()).isTrue();
    }

    @Test
    void isAtomicUnderConcurrency() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                exec.submit(() -> {
                    start.await();
                    results.add(limiter.tryConsume(key).allowed());
                    return null;
                });
            }
            start.countDown();
        }
        // 100 simultaneous requests: only the burst capacity (plus the few tokens refilled while they run)
        // gets through. A non-atomic read-then-write would let far more through.
        long allowed = results.stream().filter(Boolean::booleanValue).count();
        assertThat(allowed).isBetween(20L, 23L);
    }
}

package com.example.urlshortner2.ratelimit;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import com.example.urlshortner2.config.AppProperties;

/**
 * Distributed token bucket: state lives in Redis (shared by all app instances) and the
 * read-refill-take-write sequence runs atomically as a Lua script (sent once, then called via EVALSHA).
 */
@Component
public class TokenBucketLimiter {

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds, int limit) {}

    static final String PREFIX = "rl:";

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final StringRedisTemplate redis;
    private final int capacity;
    private final double refillPerSecond;

    public TokenBucketLimiter(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.capacity = props.rateLimit().capacity();
        this.refillPerSecond = props.rateLimit().refillPerSecond();
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("ratelimit.lua")));
        s.setResultType(List.class);
        this.script = s;
    }

    public Decision tryConsume(String bucketId) {
        List<?> r = redis.execute(script, List.of(PREFIX + bucketId),
                String.valueOf(capacity), String.valueOf(refillPerSecond));
        if (r == null || r.size() < 3) {
            throw new IllegalStateException("unexpected rate limiter reply: " + r);
        }
        boolean allowed = ((Number) r.get(0)).longValue() == 1L;
        long remaining = ((Number) r.get(1)).longValue();
        long retryMs = ((Number) r.get(2)).longValue();
        return new Decision(allowed, remaining, allowed ? 0 : Math.max(1, (retryMs + 999) / 1000), capacity);
    }
}

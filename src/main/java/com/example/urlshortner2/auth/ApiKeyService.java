package com.example.urlshortner2.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.example.urlshortner2.link.CodeGenerator;
import com.example.urlshortner2.storage.ApiKeyRepository;
import com.example.urlshortner2.storage.ApiKeyRepository.ApiKeyRecord;

/**
 * API keys look like {@code <keyId>.<secret>}. Only SHA-256(secret) is stored, so a database leak
 * does not leak usable keys (a fast hash is fine because secrets are 32 random bytes, not passwords).
 * Comparison is constant-time. Lookups are cached in memory for 60 s.
 */
@Service
public class ApiKeyService {

    public record CreatedKey(String keyId, String apiKey, String name) {}

    public static class DuplicateKeyNameException extends RuntimeException {
        public DuplicateKeyNameException(String name) {
            super("an API key named '" + name + "' already exists");
        }
    }

    private record CachedLookup(Optional<ApiKeyRecord> record, long expiresAtNanos) {}

    private static final SecureRandom RNG = new SecureRandom();
    private static final long LOOKUP_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_CACHED = 10_000;

    private final ApiKeyRepository repo;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedLookup> lookups = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public CreatedKey create(String name) {
        String keyId = "k_" + CodeGenerator.newCode(10);
        byte[] secretBytes = new byte[32];
        RNG.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        if (!repo.insert(new ApiKeyRecord(keyId, sha256(secret), name, clock.instant(), true))) {
            throw new DuplicateKeyNameException(name);
        }
        return new CreatedKey(keyId, keyId + "." + secret, name);
    }

    /** @return the keyId if the header value is a valid, active key */
    public Optional<String> authenticate(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        int dot = headerValue.indexOf('.');
        if (dot <= 0 || dot == headerValue.length() - 1) {
            return Optional.empty();
        }
        String keyId = headerValue.substring(0, dot);
        String secret = headerValue.substring(dot + 1);
        Optional<ApiKeyRecord> rec = lookup(keyId);
        if (rec.isEmpty() || !rec.get().active()) {
            return Optional.empty();
        }
        return MessageDigest.isEqual(sha256(secret), rec.get().secretHash()) ? Optional.of(keyId) : Optional.empty();
    }

    private Optional<ApiKeyRecord> lookup(String keyId) {
        long now = System.nanoTime();
        CachedLookup c = lookups.get(keyId);
        if (c != null && now - c.expiresAtNanos() < 0) {
            return c.record();
        }
        Optional<ApiKeyRecord> rec = repo.findById(keyId);
        if (lookups.size() >= MAX_CACHED) {
            lookups.clear(); // crude bound; keys are few, this only guards against floods of random keyIds
        }
        lookups.put(keyId, new CachedLookup(rec, now + LOOKUP_TTL_NANOS));
        return rec;
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

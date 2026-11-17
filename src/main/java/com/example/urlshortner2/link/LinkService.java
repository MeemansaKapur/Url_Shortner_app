package com.example.urlshortner2.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.example.urlshortner2.cache.CachedLinkResolver;
import com.example.urlshortner2.storage.LinkRepository;

public class LinkService {

    static final int MAX_ATTEMPTS = 3;

    private final LinkRepository repo;
    private final CachedLinkResolver resolver;
    private final int codeLength;
    private final Clock clock;

    public LinkService(LinkRepository repo, CachedLinkResolver resolver, int codeLength, Clock clock) {
        this.repo = repo;
        this.resolver = resolver;
        this.codeLength = codeLength;
        this.clock = clock;
    }

    /**
     * Creates a link with a random code. A duplicate code is detected atomically by the database's
     * unique key; we retry with a new code up to 3 times (3 collisions in a row means something is broken).
     */
    public Link create(String url, Long ttlSeconds, String ownerKeyId, String ownHost) {
        UrlValidator.validate(url, ownHost);
        Instant now = clock.instant();
        Instant expiresAt = ttlSeconds == null ? null : now.plus(Duration.ofSeconds(ttlSeconds));
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Link link = new Link(CodeGenerator.newCode(codeLength), url.strip(), now, expiresAt, ownerKeyId);
            if (repo.insertIfAbsent(link)) {
                resolver.evict(link.code()); // drop a negative-cache entry if this code was probed before
                return link;
            }
        }
        throw new IllegalStateException("Could not generate a unique code after " + MAX_ATTEMPTS + " attempts");
    }

    public Optional<String> resolve(String code) {
        if (!CodeGenerator.isValidCode(code)) {
            return Optional.empty();
        }
        return resolver.resolve(code);
    }

    public boolean delete(String code, String ownerKeyId) {
        boolean deleted = repo.delete(code, ownerKeyId);
        if (deleted) {
            resolver.evict(code);
        }
        return deleted;
    }
}

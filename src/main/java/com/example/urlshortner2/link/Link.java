package com.example.urlshortner2.link;

import java.time.Instant;

/**
 * A short link. {@code expiresAt == null} means the link never expires.
 */
public record Link(String code, String url, Instant createdAt, Instant expiresAt, String ownerKeyId) {

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}

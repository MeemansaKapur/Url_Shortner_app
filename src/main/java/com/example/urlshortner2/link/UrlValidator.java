package com.example.urlshortner2.link;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Only http/https with a host, at most 2,048 chars, and never pointing back at this service
 * (prevents redirect loops). Rejects javascript:, data:, file: and similar schemes.
 */
public final class UrlValidator {

    public static final int MAX_LENGTH = 2048;

    private UrlValidator() {
    }

    /** @throws IllegalArgumentException with a client-facing message if the URL is not allowed */
    public static void validate(String url, String ownHost) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("url is longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url must have a host");
        }
        if (ownHost != null && host.equalsIgnoreCase(ownHost)) {
            throw new IllegalArgumentException("url must not point to this shortener");
        }
    }
}

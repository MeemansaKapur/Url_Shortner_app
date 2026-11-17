package com.example.urlshortner2.api;

import java.net.URI;
import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.urlshortner2.auth.ApiKeyFilter;
import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.link.LinkService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@RestController
public class LinkController {

    public record CreateLinkRequest(
            @NotBlank(message = "url is required") String url,
            @Min(value = 60, message = "ttlSeconds must be at least 60")
            @Max(value = 31_536_000, message = "ttlSeconds must be at most 1 year") Long ttlSeconds) {}

    public record LinkResponse(String code, String shortUrl, String url, Instant expiresAt) {}

    private final LinkService links;

    public LinkController(LinkService links) {
        this.links = links;
    }

    @PostMapping("/api/links")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest body,
                                               @RequestAttribute(ApiKeyFilter.KEY_ID_ATTR) String keyId,
                                               HttpServletRequest req) {
        Link link = links.create(body.url(), body.ttlSeconds(), keyId, req.getServerName());
        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{code}").buildAndExpand(link.code()).toUriString();
        return ResponseEntity.created(URI.create(shortUrl))
                .body(new LinkResponse(link.code(), shortUrl, link.url(), link.expiresAt()));
    }

    @DeleteMapping("/api/links/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code,
                                       @RequestAttribute(ApiKeyFilter.KEY_ID_ATTR) String keyId) {
        return links.delete(code, keyId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * 302 (not 301): browsers don't cache it, so expiry and deletion take effect immediately.
     * Unknown and expired codes both return 404, which reveals nothing about past links.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return links.resolve(code)
                .map(url -> ResponseEntity.status(302).location(URI.create(url)).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

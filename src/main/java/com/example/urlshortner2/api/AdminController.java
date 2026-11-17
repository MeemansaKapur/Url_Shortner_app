package com.example.urlshortner2.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.urlshortner2.auth.ApiKeyService;
import com.example.urlshortner2.config.AppProperties;
import com.example.urlshortner2.link.CodeGenerator;
import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.storage.LinkRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Operator endpoints, guarded by {@code X-Admin-Token} (env ADMIN_TOKEN). Disabled (404) when no token is set.
 */
@RestController
public class AdminController {

    public record CreateKeyRequest(@NotBlank @Size(max = 100) String name) {}

    static final int MAX_SEED = 1_000_000;

    private final ApiKeyService keys;
    private final LinkRepository repo;
    private final AppProperties props;
    private final Clock clock;

    public AdminController(ApiKeyService keys, LinkRepository repo, AppProperties props, Clock clock) {
        this.keys = keys;
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** Issues an API key. The full key is returned once and never stored in plain text. */
    @PostMapping("/admin/keys")
    public ResponseEntity<?> createKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                       @Valid @RequestBody CreateKeyRequest body) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(keys.create(body.name()));
    }

    /**
     * Bulk-inserts load-test links directly through the repository (bypassing the per-key rate limit)
     * and returns their codes for k6. {@code expiringPercent} of them get a 30-day expiry.
     */
    @PostMapping("/admin/seed")
    public ResponseEntity<?> seed(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                  @RequestParam(defaultValue = "100000") int count,
                                  @RequestParam(defaultValue = "20") int expiringPercent) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        if (count < 1 || count > MAX_SEED || expiringPercent < 0 || expiringPercent > 100) {
            return ResponseEntity.badRequest().body(new ErrorHandler.ErrorBody("invalid count or expiringPercent"));
        }
        Instant now = clock.instant();
        Instant expiry = now.plus(Duration.ofDays(30));
        List<String> codes = new ArrayList<>(count);
        List<Link> batch = new ArrayList<>(1000);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            String code = CodeGenerator.newCode(props.codeLength());
            boolean expiring = rnd.nextInt(100) < expiringPercent;
            batch.add(new Link(code, "https://example.com/seed/" + i + "?ref=" + code, now,
                    expiring ? expiry : null, "seed"));
            if (batch.size() == 1000 || i == count - 1) {
                codes.addAll(repo.insertBatch(batch));
                batch.clear();
            }
        }
        return ResponseEntity.ok(codes);
    }

    private boolean authorized(String token) {
        String expected = props.adminToken();
        if (expected == null || expected.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}

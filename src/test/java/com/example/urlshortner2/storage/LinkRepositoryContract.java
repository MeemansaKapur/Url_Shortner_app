package com.example.urlshortner2.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.urlshortner2.link.CodeGenerator;
import com.example.urlshortner2.link.Link;

/**
 * One test suite, run against every {@link LinkRepository} implementation. Proves both backends behave
 * identically, which is what makes the MongoDB vs PostgreSQL benchmark a fair comparison.
 */
public abstract class LinkRepositoryContract {

    protected abstract LinkRepository repo();

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private Link link(Instant expiresAt, String owner) {
        return new Link(CodeGenerator.newCode(10), "https://example.com/" + CodeGenerator.newCode(5), now, expiresAt, owner);
    }

    @Test
    void insertThenFind() {
        Link l = link(now.plus(Duration.ofDays(1)), "k_owner");
        assertThat(repo().insertIfAbsent(l)).isTrue();
        assertThat(repo().findByCode(l.code(), now)).contains(l);
    }

    @Test
    void permanentLinkIsFound() {
        Link l = link(null, "k_owner");
        repo().insertIfAbsent(l);
        assertThat(repo().findByCode(l.code(), now.plus(Duration.ofDays(3650)))).contains(l);
    }

    @Test
    void duplicateCodeReturnsFalse() {
        Link l = link(null, "k_a");
        assertThat(repo().insertIfAbsent(l)).isTrue();
        Link sameCode = new Link(l.code(), "https://other.example", now, null, "k_b");
        assertThat(repo().insertIfAbsent(sameCode)).isFalse();
        assertThat(repo().findByCode(l.code(), now).orElseThrow().url()).isEqualTo(l.url());
    }

    @Test
    void expiredLinkIsNotFoundEvenBeforeCleanup() {
        Link l = link(now.plusSeconds(10), "k_owner");
        repo().insertIfAbsent(l);
        assertThat(repo().findByCode(l.code(), now)).isPresent();
        assertThat(repo().findByCode(l.code(), now.plusSeconds(10))).isEmpty();
        assertThat(repo().findByCode(l.code(), now.plusSeconds(11))).isEmpty();
    }

    @Test
    void unknownCodeIsNotFound() {
        assertThat(repo().findByCode("doesNotExist", now)).isEmpty();
    }

    @Test
    void deleteOnlyByOwner() {
        Link l = link(null, "k_owner");
        repo().insertIfAbsent(l);
        assertThat(repo().delete(l.code(), "k_someone_else")).isFalse();
        assertThat(repo().findByCode(l.code(), now)).isPresent();
        assertThat(repo().delete(l.code(), "k_owner")).isTrue();
        assertThat(repo().findByCode(l.code(), now)).isEmpty();
        assertThat(repo().delete(l.code(), "k_owner")).isFalse();
    }

    @Test
    void insertBatchSkipsExistingCodes() {
        Link existing = link(null, "seed");
        repo().insertIfAbsent(existing);
        Link a = link(null, "seed");
        Link b = link(now.plus(Duration.ofDays(30)), "seed");
        Link dup = new Link(existing.code(), "https://dup.example", now, null, "seed");
        List<String> inserted = repo().insertBatch(List.of(a, dup, b));
        assertThat(inserted).containsExactly(a.code(), b.code());
        assertThat(repo().findByCode(b.code(), now)).contains(b);
    }
}

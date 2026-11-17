package com.example.urlshortner2.storage.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.storage.LinkRepository;

/**
 * Table {@code links(code PK, url, created_at, expires_at, owner)}.
 * Postgres has no TTL: expired rows are removed by {@link ExpiryCleanupJob} in small batches,
 * and the read path filters them out.
 */
public class PostgresLinkRepository implements LinkRepository {

    private static final String INSERT = """
            INSERT INTO links (code, url, created_at, expires_at, owner)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (code) DO NOTHING""";

    private static final String FIND = """
            SELECT code, url, created_at, expires_at, owner FROM links
            WHERE code = ? AND (expires_at IS NULL OR expires_at > ?)""";

    private final JdbcTemplate jdbc;

    public PostgresLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Link l) {
        return jdbc.update(INSERT, l.code(), l.url(), ts(l.createdAt()), ts(l.expiresAt()), l.ownerKeyId()) == 1;
    }

    @Override
    public Optional<Link> findByCode(String code, Instant now) {
        List<Link> rows = jdbc.query(FIND, PostgresLinkRepository::mapRow, code, ts(now));
        return rows.stream().findFirst();
    }

    @Override
    public boolean delete(String code, String ownerKeyId) {
        return jdbc.update("DELETE FROM links WHERE code = ? AND owner = ?", code, ownerKeyId) == 1;
    }

    @Override
    public List<String> insertBatch(List<Link> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        List<Object[]> args = batch.stream()
                .map(l -> new Object[] {l.code(), l.url(), ts(l.createdAt()), ts(l.expiresAt()), l.ownerKeyId()})
                .toList();
        int[] counts = jdbc.batchUpdate(INSERT, args);
        List<String> inserted = new ArrayList<>(batch.size());
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 1) {
                inserted.add(batch.get(i).code());
            }
        }
        return inserted;
    }

    /** Deletes up to {@code limit} expired rows. Small batches avoid long locks and WAL spikes. */
    public int deleteExpiredBatch(int limit) {
        return jdbc.update("""
                DELETE FROM links
                WHERE code IN (SELECT code FROM links WHERE expires_at < now() LIMIT ?)""", limit);
    }

    private static Link mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        return new Link(
                rs.getString("code"),
                rs.getString("url"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getString("owner"));
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}

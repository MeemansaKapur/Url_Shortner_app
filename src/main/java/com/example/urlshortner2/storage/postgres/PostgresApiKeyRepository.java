package com.example.urlshortner2.storage.postgres;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.urlshortner2.storage.ApiKeyRepository;

public class PostgresApiKeyRepository implements ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public PostgresApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(ApiKeyRecord k) {
        try {
            jdbc.update("INSERT INTO api_keys (key_id, secret_hash, name, created_at, active) VALUES (?, ?, ?, ?, ?)",
                    k.keyId(), k.secretHash(), k.name(), OffsetDateTime.ofInstant(k.createdAt(), ZoneOffset.UTC), k.active());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<ApiKeyRecord> findById(String keyId) {
        return jdbc.query("SELECT key_id, secret_hash, name, created_at, active FROM api_keys WHERE key_id = ?",
                (rs, n) -> new ApiKeyRecord(
                        rs.getString("key_id"),
                        rs.getBytes("secret_hash"),
                        rs.getString("name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("active")),
                keyId).stream().findFirst();
    }
}

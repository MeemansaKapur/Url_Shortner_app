package com.example.urlshortner2.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.urlshortner2.storage.postgres.PostgresLinkRepository;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
class PostgresLinkRepositoryTest extends LinkRepositoryContract {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static PostgresLinkRepository repo;

    @BeforeAll
    static void setUp() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        repo = new PostgresLinkRepository(jdbc);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    @Override
    protected LinkRepository repo() {
        return repo;
    }

    @Test
    void cleanupJobDeletesOnlyExpiredRows() {
        OffsetDateTime past = OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC);
        OffsetDateTime future = OffsetDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        jdbc.update("INSERT INTO links (code, url, expires_at, owner) VALUES ('cleanOld', 'https://a.example', ?, 'o')", past);
        jdbc.update("INSERT INTO links (code, url, expires_at, owner) VALUES ('cleanNew', 'https://b.example', ?, 'o')", future);
        jdbc.update("INSERT INTO links (code, url, owner) VALUES ('cleanPerm', 'https://c.example', 'o')");

        while (repo.deleteExpiredBatch(1000) == 1000) {
            // drain
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM links WHERE code = 'cleanOld'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM links WHERE code IN ('cleanNew','cleanPerm')", Integer.class)).isEqualTo(2);
    }
}

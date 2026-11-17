package com.example.urlshortner2.config;

import javax.sql.DataSource;

import org.bson.Document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.example.urlshortner2.storage.ApiKeyRepository;
import com.example.urlshortner2.storage.LinkRepository;
import com.example.urlshortner2.storage.mongo.MongoApiKeyRepository;
import com.example.urlshortner2.storage.mongo.MongoLinkRepository;
import com.example.urlshortner2.storage.postgres.ExpiryCleanupJob;
import com.example.urlshortner2.storage.postgres.PostgresApiKeyRepository;
import com.example.urlshortner2.storage.postgres.PostgresLinkRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Selects the storage backend from {@code app.storage}. Only the selected backend's client is created,
 * so the app needs only that database to be running. (Static nested configurations are found by component scanning.)
 */
public final class StorageConfig {

    private StorageConfig() {
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.storage", havingValue = "mongo")
    static class MongoStorage {

        @Bean(destroyMethod = "close")
        MongoClient mongoClient(AppProperties props) {
            var m = props.mongo();
            return MongoClients.create(MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(m.uri()))
                    .applyToConnectionPoolSettings(p -> p.maxSize(m.maxPoolSize()))
                    .applicationName("url-shortener")
                    .build());
        }

        @Bean
        MongoDatabase mongoDatabase(MongoClient client, AppProperties props) {
            return client.getDatabase(props.mongo().database());
        }

        @Bean
        LinkRepository linkRepository(MongoDatabase db) {
            return new MongoLinkRepository(db);
        }

        @Bean
        ApiKeyRepository apiKeyRepository(MongoDatabase db) {
            return new MongoApiKeyRepository(db);
        }

        @Bean
        StorageHealthIndicator storageHealthIndicator(MongoDatabase db) {
            return new StorageHealthIndicator("mongodb", () -> db.runCommand(new Document("ping", 1)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.storage", havingValue = "postgres")
    static class PostgresStorage {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(AppProperties props) {
            var p = props.postgres();
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(p.url());
            cfg.setUsername(p.username());
            cfg.setPassword(p.password());
            cfg.setMaximumPoolSize(p.maxPoolSize());
            cfg.setPoolName("pg");
            HikariDataSource ds = new HikariDataSource(cfg);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        PostgresLinkRepository linkRepository(JdbcTemplate jdbc) {
            return new PostgresLinkRepository(jdbc);
        }

        @Bean
        ApiKeyRepository apiKeyRepository(JdbcTemplate jdbc) {
            return new PostgresApiKeyRepository(jdbc);
        }

        @Bean
        ExpiryCleanupJob expiryCleanupJob(PostgresLinkRepository repo) {
            return new ExpiryCleanupJob(repo);
        }

        @Bean
        StorageHealthIndicator storageHealthIndicator(JdbcTemplate jdbc) {
            return new StorageHealthIndicator("postgresql", () -> jdbc.queryForObject("SELECT 1", Integer.class));
        }
    }
}

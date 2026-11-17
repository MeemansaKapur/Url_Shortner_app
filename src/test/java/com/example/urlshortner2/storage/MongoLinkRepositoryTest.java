package com.example.urlshortner2.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.example.urlshortner2.storage.mongo.MongoLinkRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

@Testcontainers
class MongoLinkRepositoryTest extends LinkRepositoryContract {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    static MongoClient client;
    static MongoDatabase db;
    static LinkRepository repo;

    @BeforeAll
    static void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        db = client.getDatabase("test");
        repo = new MongoLinkRepository(db);
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    @Override
    protected LinkRepository repo() {
        return repo;
    }

    @Test
    void hasTtlIndexOnExpiresAtAndUniqueId() {
        List<Document> indexes = db.getCollection(MongoLinkRepository.COLLECTION).listIndexes().into(new ArrayList<>());
        Document ttl = indexes.stream()
                .filter(i -> MongoLinkRepository.TTL_INDEX.equals(i.getString("name"))).findFirst().orElseThrow();
        assertThat(ttl.get("key", Document.class)).isEqualTo(new Document("expiresAt", 1));
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isZero();
        assertThat(indexes).anyMatch(i -> "_id_".equals(i.getString("name")));
    }
}

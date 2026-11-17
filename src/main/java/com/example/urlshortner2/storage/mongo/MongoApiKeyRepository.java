package com.example.urlshortner2.storage.mongo;

import java.util.Date;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.Binary;

import com.example.urlshortner2.storage.ApiKeyRepository;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * { _id: keyId, hash: BinData (SHA-256 of the secret), name, createdAt, active }.
 * Unique indexes: _id (built in) and name.
 */
public class MongoApiKeyRepository implements ApiKeyRepository {

    public static final String COLLECTION = "api_keys";

    private final MongoCollection<Document> keys;

    public MongoApiKeyRepository(MongoDatabase db) {
        this.keys = db.getCollection(COLLECTION);
        keys.createIndex(Indexes.ascending("name"), new IndexOptions().unique(true).name("uniq_name"));
    }

    @Override
    public boolean insert(ApiKeyRecord key) {
        try {
            keys.insertOne(new Document("_id", key.keyId())
                    .append("hash", new Binary(key.secretHash()))
                    .append("name", key.name())
                    .append("createdAt", Date.from(key.createdAt()))
                    .append("active", key.active()));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<ApiKeyRecord> findById(String keyId) {
        Document d = keys.find(Filters.eq("_id", keyId)).first();
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new ApiKeyRecord(
                d.getString("_id"),
                d.get("hash", Binary.class).getData(),
                d.getString("name"),
                d.getDate("createdAt").toInstant(),
                d.getBoolean("active", false)));
    }
}

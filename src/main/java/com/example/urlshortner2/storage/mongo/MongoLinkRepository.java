package com.example.urlshortner2.storage.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.example.urlshortner2.link.Link;
import com.example.urlshortner2.storage.LinkRepository;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;

/**
 * Document model:
 * <pre>{ _id: "aB3xK9q", url: "...", createdAt: ISODate, expiresAt: ISODate (absent = permanent), owner: "k_..." }</pre>
 * <ul>
 *   <li>{@code _id} = short code: the built-in unique index enforces code uniqueness and serves the redirect lookup.</li>
 *   <li>TTL index on {@code expiresAt} with expireAfterSeconds=0: each document expires at its own time.</li>
 * </ul>
 */
public class MongoLinkRepository implements LinkRepository {

    public static final String COLLECTION = "links";
    public static final String TTL_INDEX = "ttl_expiresAt";

    private final MongoCollection<Document> links;

    public MongoLinkRepository(MongoDatabase db) {
        this.links = db.getCollection(COLLECTION);
        ensureIndexes();
    }

    private void ensureIndexes() {
        // _id already has a unique index; only the TTL index is added.
        links.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name(TTL_INDEX));
    }

    @Override
    public boolean insertIfAbsent(Link link) {
        try {
            links.insertOne(toDocument(link));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<Link> findByCode(String code, Instant now) {
        // The TTL monitor runs about every 60 s, so the read path checks expiry itself.
        Bson filter = Filters.and(
                Filters.eq("_id", code),
                Filters.or(Filters.exists("expiresAt", false), Filters.gt("expiresAt", Date.from(now))));
        return Optional.ofNullable(links.find(filter).first()).map(MongoLinkRepository::toLink);
    }

    @Override
    public boolean delete(String code, String ownerKeyId) {
        return links.deleteOne(Filters.and(Filters.eq("_id", code), Filters.eq("owner", ownerKeyId)))
                .getDeletedCount() == 1;
    }

    @Override
    public List<String> insertBatch(List<Link> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        List<Document> docs = batch.stream().map(MongoLinkRepository::toDocument).toList();
        Set<Integer> failed = new HashSet<>();
        try {
            links.insertMany(docs, new InsertManyOptions().ordered(false));
        } catch (MongoBulkWriteException e) {
            for (BulkWriteError err : e.getWriteErrors()) {
                if (ErrorCategory.fromErrorCode(err.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                failed.add(err.getIndex());
            }
        }
        List<String> inserted = new ArrayList<>(batch.size() - failed.size());
        for (int i = 0; i < batch.size(); i++) {
            if (!failed.contains(i)) {
                inserted.add(batch.get(i).code());
            }
        }
        return inserted;
    }

    static Document toDocument(Link link) {
        Document d = new Document("_id", link.code())
                .append("url", link.url())
                .append("createdAt", Date.from(link.createdAt()))
                .append("owner", link.ownerKeyId());
        if (link.expiresAt() != null) {
            d.append("expiresAt", Date.from(link.expiresAt()));
        }
        return d;
    }

    static Link toLink(Document d) {
        Date expires = d.getDate("expiresAt");
        return new Link(
                d.getString("_id"),
                d.getString("url"),
                d.getDate("createdAt").toInstant(),
                expires == null ? null : expires.toInstant(),
                d.getString("owner"));
    }
}

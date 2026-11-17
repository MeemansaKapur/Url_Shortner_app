package com.example.urlshortner2.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End to end over real HTTP with real MongoDB and Redis: auth, rate limit, create, redirect, delete. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.storage=mongo",
        "app.admin-token=test-admin-token",
        "app.rate-limit.capacity=3",
        "app.rate-limit.refill-per-second=0.1",
})
class ApiIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.mongo.uri", MONGO::getConnectionString);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String newApiKey() throws Exception {
        var res = send("POST", "/admin/keys", "{\"name\":\"" + UUID.randomUUID() + "\"}", "X-Admin-Token", "test-admin-token");
        assertThat(res.statusCode()).isEqualTo(201);
        return json.readTree(res.body()).get("apiKey").asString();
    }

    @Test
    void createRedirectDeleteLifecycle() throws Exception {
        String key = newApiKey();

        var created = send("POST", "/api/links", "{\"url\":\"https://example.com/hello\",\"ttlSeconds\":3600}", "X-API-Key", key);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        String code = body.get("code").asString();
        assertThat(code).matches("[0-9A-Za-z]{7}");
        assertThat(body.get("shortUrl").asString()).endsWith("/" + code);
        assertThat(body.get("expiresAt").isNull()).isFalse();
        assertThat(created.headers().firstValue("X-RateLimit-Limit")).contains("3");

        var redirect = send("GET", "/" + code, null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains("https://example.com/hello");

        // Served from cache the second time; same answer
        assertThat(send("GET", "/" + code, null).statusCode()).isEqualTo(302);

        String otherKey = newApiKey();
        assertThat(send("DELETE", "/api/links/" + code, null, "X-API-Key", otherKey).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/links/" + code, null, "X-API-Key", key).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/" + code, null).statusCode()).isEqualTo(404);
    }

    @Test
    void unknownCodeIs404() throws Exception {
        assertThat(send("GET", "/zzzzzzz", null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/favicon.ico", null).statusCode()).isEqualTo(404);
    }

    @Test
    void apiRequiresValidKey() throws Exception {
        String body = "{\"url\":\"https://example.com\"}";
        assertThat(send("POST", "/api/links", body).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/links", body, "X-API-Key", "k_fake.secret").statusCode()).isEqualTo(401);
        String key = newApiKey();
        String tampered = key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A");
        assertThat(send("POST", "/api/links", body, "X-API-Key", tampered).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsDangerousUrls() throws Exception {
        String key = newApiKey();
        var res = send("POST", "/api/links", "{\"url\":\"javascript:alert(1)\"}", "X-API-Key", key);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("http");
    }

    @Test
    void rateLimitsLinkCreationPerKey() throws Exception {
        String key = newApiKey();
        String body = "{\"url\":\"https://example.com/rl\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/api/links", body, "X-API-Key", key).statusCode()).isEqualTo(201);
        }
        var limited = send("POST", "/api/links", body, "X-API-Key", key);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).contains("0");

        // A different key has its own bucket
        assertThat(send("POST", "/api/links", body, "X-API-Key", newApiKey()).statusCode()).isEqualTo(201);
    }

    @Test
    void adminEndpointsHiddenWithoutToken() throws Exception {
        assertThat(send("POST", "/admin/keys", "{\"name\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/admin/keys", "{\"name\":\"x\"}", "X-Admin-Token", "wrong").statusCode()).isEqualTo(404);
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        var res = send("GET", "/actuator/health", null);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode health = json.readTree(res.body());
        assertThat(health.get("status").asString()).isEqualTo("UP");
        assertThat(health.at("/components/storage/status").asString()).isEqualTo("UP");
        assertThat(health.at("/components/storage/details/backend").asString()).isEqualTo("mongodb");
        assertThat(health.at("/components/redis/status").asString()).isEqualTo("UP");
    }
}

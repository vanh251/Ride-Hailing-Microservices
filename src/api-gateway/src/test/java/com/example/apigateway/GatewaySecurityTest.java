package com.example.apigateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks JWT authentication, role rules and the X-User-Id header at the gateway (docs/api.md §1, §10).
 * trip-service is replaced by a local stub that records the headers it receives; every other backend points at a
 * closed port, so a request that passes the security layer fails with 5xx instead of 401/403.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=" + TestTokens.TEST_SECRET,
                "IDENTITY_SERVICE_URL=http://localhost:1",
                "DRIVER_SERVICE_URL=http://localhost:1",
                "MATCHING_SERVICE_URL=http://localhost:1",
                "PRICING_SERVICE_URL=http://localhost:1",
                "PAYMENT_SERVICE_URL=http://localhost:1",
                "NOTIFICATION_SERVICE_URL=http://localhost:1"
        })
class GatewaySecurityTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String FORGED_USER_ID = "99999999-9999-4999-8999-999999999999";

    private static final AtomicReference<Headers> BACKEND_HEADERS = new AtomicReference<>();
    private static final HttpServer BACKEND = startBackend();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("TRIP_SERVICE_URL", () -> "http://localhost:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @BeforeEach
    void resetBackend() {
        BACKEND_HEADERS.set(null);
    }

    @Test
    @DisplayName("Protected route without a token returns 401 MISSING_TOKEN and never reaches the backend")
    void protectedRoute_withoutToken_returns401MissingToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/trips/t1", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"MISSING_TOKEN\"");
        assertThat(BACKEND_HEADERS.get()).isNull();
    }

    @Test
    @DisplayName("Token signed with another secret returns 401 INVALID_TOKEN")
    void protectedRoute_tokenSignedWithOtherSecret_returns401InvalidToken() throws Exception {
        String token = TestTokens.token(TestTokens.OTHER_SECRET, USER_ID.toString(), "PASSENGER",
                Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
        assertThat(BACKEND_HEADERS.get()).isNull();
    }

    @Test
    @DisplayName("Garbage token returns 401 INVALID_TOKEN")
    void protectedRoute_malformedToken_returns401InvalidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/trips/t1", "not-a-jwt");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
    }

    @Test
    @DisplayName("Expired token returns 401 TOKEN_EXPIRED")
    void protectedRoute_expiredToken_returns401TokenExpired() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, USER_ID.toString(), "PASSENGER",
                Instant.now().minusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"TOKEN_EXPIRED\"");
    }

    @Test
    @DisplayName("Token without a role claim returns 401 INVALID_TOKEN")
    void protectedRoute_tokenWithoutRole_returns401InvalidToken() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, USER_ID.toString(), null,
                Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
    }

    @Test
    @DisplayName("Token with an unknown role returns 401 INVALID_TOKEN")
    void protectedRoute_tokenWithUnknownRole_returns401InvalidToken() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, USER_ID.toString(), "SUPERUSER",
                Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
    }

    @Test
    @DisplayName("Token whose subject is not a UUID returns 401 INVALID_TOKEN")
    void protectedRoute_tokenWithNonUuidSubject_returns401InvalidToken() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, "not-a-uuid", "PASSENGER",
                Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
    }

    @Test
    @DisplayName("401 body follows the standard error format and carries the correlation id")
    void protectedRoute_withoutToken_returnsStandardErrorBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/trips/t1"))
                .header("X-Correlation-ID", "corr-123")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body())
                .contains("\"timestamp\":")
                .contains("\"status\":401")
                .contains("\"error\":\"Unauthorized\"")
                .contains("\"message\":")
                .contains("\"path\":\"/api/trips/t1\"")
                .contains("\"correlationId\":\"corr-123\"")
                .contains("\"code\":\"MISSING_TOKEN\"");
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            "DRIVER,    POST,  /api/trips",
            "DRIVER,    POST,  /api/trips/t1/cancel",
            "DRIVER,    GET,   /api/drivers/nearby",
            "PASSENGER, POST,  /api/trips/t1/accept",
            "PASSENGER, POST,  /api/trips/t1/start",
            "PASSENGER, POST,  /api/trips/t1/complete",
            "PASSENGER, PATCH, /api/drivers/d1/status",
            "PASSENGER, PATCH, /api/drivers/d1/location"
    })
    @DisplayName("A role that the endpoint does not allow returns 403 ACCESS_DENIED")
    void roleRestrictedRoute_wrongRole_returns403AccessDenied(String role, String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, TestTokens.token(USER_ID, role));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"");
        assertThat(BACKEND_HEADERS.get()).isNull();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            "PASSENGER, POST,  /api/trips",
            "PASSENGER, POST,  /api/trips/t1/cancel",
            "PASSENGER, GET,   /api/drivers/nearby",
            "DRIVER,    POST,  /api/trips/t1/accept",
            "DRIVER,    POST,  /api/trips/t1/start",
            "DRIVER,    POST,  /api/trips/t1/complete",
            "DRIVER,    PATCH, /api/drivers/d1/status",
            "DRIVER,    PATCH, /api/drivers/d1/location",
            "PASSENGER, GET,   /api/trips/t1",
            "DRIVER,    GET,   /api/trips/t1",
            "DRIVER,    GET,   /api/users/u1",
            "PASSENGER, GET,   /api/drivers/d1",
            "PASSENGER, GET,   /api/payments/t1",
            "PASSENGER, POST,  /api/pricing/quote",
            "DRIVER,    GET,   /api/notifications/trip/t1"
    })
    @DisplayName("An allowed role passes the security layer")
    void protectedRoute_allowedRole_passesSecurity(String role, String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, TestTokens.token(USER_ID, role));

        assertThat(response.statusCode()).isNotIn(401, 403, 404);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/auth/login",
            "POST, /api/auth/register/passenger",
            "POST, /api/auth/register/driver",
            "GET,  /api/drivers/available",
            "GET,  /actuator/health"
    })
    @DisplayName("Public routes need no token")
    void publicRoute_withoutToken_passesSecurity(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, null);

        assertThat(response.statusCode()).isNotIn(401, 403, 404);
    }

    @Test
    @DisplayName("A public route still works when the request carries an invalid token")
    void publicRoute_invalidToken_passesSecurity() throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/login", "not-a-jwt");

        assertThat(response.statusCode()).isNotIn(401, 403, 404);
    }

    @Test
    @DisplayName("Only the documented auth endpoints are public: other /api/auth paths need a token")
    void undocumentedAuthPath_withoutToken_returns401() throws Exception {
        assertThat(send("POST", "/api/auth/refresh", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("The backend receives X-User-Id = token subject, and the Authorization header is kept")
    void authenticatedRequest_forwardsUserIdAndAuthorization() throws Exception {
        String token = TestTokens.token(USER_ID, "PASSENGER");

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertThat(response.statusCode()).isEqualTo(200);
        Headers headers = BACKEND_HEADERS.get();
        assertThat(headers).isNotNull();
        assertThat(headers.get(USER_ID_HEADER)).containsExactly(USER_ID.toString());
        assertThat(headers.getFirst("Authorization")).isEqualTo(TestTokens.bearer(token));
    }

    @Test
    @DisplayName("An X-User-Id sent by the client is replaced by the token subject")
    void authenticatedRequest_forgedUserIdHeader_isReplacedByTokenSubject() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/trips"))
                .header("Authorization", TestTokens.bearer(TestTokens.token(USER_ID, "PASSENGER")))
                .header(USER_ID_HEADER, FORGED_USER_ID)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(BACKEND_HEADERS.get().get(USER_ID_HEADER)).containsExactly(USER_ID.toString());
    }

    @Test
    @DisplayName("Lower-case x-user-id from the client is dropped too")
    void authenticatedRequest_forgedLowerCaseUserIdHeader_isReplacedByTokenSubject() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/trips/t1"))
                .header("Authorization", TestTokens.bearer(TestTokens.token(USER_ID, "DRIVER")))
                .header("x-user-id", FORGED_USER_ID)
                .GET()
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(BACKEND_HEADERS.get().get(USER_ID_HEADER)).containsExactly(USER_ID.toString());
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", TestTokens.bearer(token));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                BACKEND_HEADERS.set(exchange.getRequestHeaders());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

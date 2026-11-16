package com.example.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abuse cases against the gateway's security layer (docs/security.md): forged tokens, tokens sent in the wrong
 * place, spoofed identity headers, path tricks around the access rules, internal paths, and what an error
 * response gives away. GatewaySecurityTest covers the normal 401/403 rules; this class covers attempts to get
 * around them.
 * Every backend except payment-service is one local stub that records what it receives, so each test can assert
 * that a rejected request never reached a backend. payment-service points at a closed port.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=" + TestTokens.TEST_SECRET,
                "PAYMENT_SERVICE_URL=http://localhost:1"
        })
class GatewaySecurityRegressionTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String INTERNAL_API_KEY = "local-dev-internal-api-key";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String FORGED_USER_ID = "99999999-9999-4999-8999-999999999999";
    private static final String DRIVER_ID = "d0000000-0000-4000-8000-000000000001";

    /** A request as a backend received it. */
    private record BackendRequest(String method, String uri, Map<String, List<String>> headers) {

        List<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
        }
    }

    private static final List<BackendRequest> BACKEND_REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer BACKEND = startBackend();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void backendUrls(DynamicPropertyRegistry registry) {
        for (String service : List.of("IDENTITY", "DRIVER", "TRIP", "MATCHING", "PRICING", "NOTIFICATION")) {
            registry.add(service + "_SERVICE_URL", () -> "http://localhost:" + BACKEND.getAddress().getPort());
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @BeforeEach
    void resetBackend() {
        BACKEND_REQUESTS.clear();
    }

    // ---------------------------------------------------------------- forged tokens

    @Test
    @DisplayName("An unsigned token (alg none) with valid claims is rejected")
    void protectedRoute_unsignedAlgNoneToken_returns401InvalidToken() throws Exception {
        String token = base64Url("{\"alg\":\"none\"}") + "." + base64Url(claims("PASSENGER")) + ".";

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertRejected(response, 401, "INVALID_TOKEN");
    }

    @Test
    @DisplayName("A token whose role was changed after signing is rejected")
    void roleRestrictedRoute_tokenWithTamperedRole_returns401InvalidToken() throws Exception {
        String[] parts = TestTokens.token(USER_ID, "PASSENGER").split("\\.");
        String tampered = parts[0] + "." + base64Url(claims("DRIVER")) + "." + parts[2];

        HttpResponse<String> response = send("POST", "/api/trips/t1/accept", tampered);

        assertRejected(response, 401, "INVALID_TOKEN");
    }

    @Test
    @DisplayName("A token with its signature cut off is rejected")
    void protectedRoute_tokenWithoutSignature_returns401InvalidToken() throws Exception {
        String[] parts = TestTokens.token(USER_ID, "PASSENGER").split("\\.");

        HttpResponse<String> response = send("GET", "/api/trips/t1", parts[0] + "." + parts[1] + ".");

        assertRejected(response, 401, "INVALID_TOKEN");
    }

    @Test
    @DisplayName("A correctly signed token without a subject is rejected")
    void protectedRoute_tokenWithoutSubject_returns401InvalidToken() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, null, "PASSENGER", java.time.Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertRejected(response, 401, "INVALID_TOKEN");
    }

    // ---------------------------------------------------------------- token in the wrong place

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"access_token", "token", "jwt"})
    @DisplayName("A valid token in the query string is not accepted")
    void protectedRoute_tokenInQueryString_returns401MissingToken(String parameter) throws Exception {
        String path = "/api/trips/t1?" + parameter + "=" + TestTokens.token(USER_ID, "PASSENGER");

        HttpResponse<String> response = send("GET", path, null);

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    @Test
    @DisplayName("A valid token in a cookie is not accepted")
    void protectedRoute_tokenInCookie_returns401MissingToken() throws Exception {
        String token = TestTokens.token(USER_ID, "PASSENGER");

        HttpResponse<String> response = send("GET", "/api/trips/t1", null,
                "Cookie", "access_token=" + token + "; Authorization=" + token);

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    @Test
    @DisplayName("Basic credentials are not an accepted way to authenticate")
    void protectedRoute_basicAuthorization_returns401MissingToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/trips/t1", null, "Authorization", "Basic dXNlcjpwYXNzd29yZA==");

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    @Test
    @DisplayName("A valid token without the Bearer scheme is not accepted")
    void protectedRoute_tokenWithoutBearerScheme_returns401MissingToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/trips/t1", null,
                "Authorization", TestTokens.token(USER_ID, "PASSENGER"));

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    // ---------------------------------------------------------------- roles

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,  /api/trips",
            "POST,  /api/trips/t1/cancel",
            "GET,   /api/drivers/nearby",
            "POST,  /api/trips/t1/accept",
            "POST,  /api/trips/t1/start",
            "POST,  /api/trips/t1/complete",
            "PATCH, /api/drivers/d1/status",
            "PATCH, /api/drivers/d1/location"
    })
    @DisplayName("ADMIN is not a wildcard: it gets 403 on PASSENGER-only and DRIVER-only endpoints")
    void roleRestrictedRoute_adminToken_returns403AccessDenied(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, TestTokens.token(USER_ID, "ADMIN"));

        assertRejected(response, 403, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("HEAD, which a backend answers like GET, does not get around the role rule of a GET endpoint")
    void passengerOnlyGetRoute_headWithDriverToken_returns403() throws Exception {
        HttpResponse<String> response = send("HEAD", "/api/drivers/nearby", TestTokens.token(USER_ID, "DRIVER"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("HEAD on the public driver search is not public: only the documented GET is")
    void publicGetRoute_headWithoutToken_returns401() throws Exception {
        HttpResponse<String> response = send("HEAD", "/api/drivers/available", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,    /api/auth/login",
            "PUT,    /api/auth/login",
            "DELETE, /api/auth/register/passenger",
            "POST,   /api/auth/register",
            "POST,   /api/auth/register/admin",
            "POST,   /api/auth/login/extra",
            "POST,   /api/drivers/available",
            "DELETE, /api/drivers/available"
    })
    @DisplayName("Public endpoints are public only for their documented method and exact path")
    void publicRoute_otherMethodOrPath_returns401(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, null);

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    // ---------------------------------------------------------------- spoofed identity headers

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/auth/login",
            "POST, /api/auth/register/passenger",
            "GET,  /api/drivers/available"
    })
    @DisplayName("On a public route the client's X-User-Id is dropped: an anonymous caller cannot claim an identity")
    void publicRoute_forgedUserIdWithoutToken_isNotForwarded(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, null, USER_ID_HEADER, FORGED_USER_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(onlyBackendRequest().header(USER_ID_HEADER)).isEmpty();
    }

    @Test
    @DisplayName("On a public route an invalid token plus a forged X-User-Id still forwards no identity")
    void publicRoute_forgedUserIdWithInvalidToken_isNotForwarded() throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/login", "not-a-jwt", USER_ID_HEADER, FORGED_USER_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(onlyBackendRequest().header(USER_ID_HEADER)).isEmpty();
    }

    @Test
    @DisplayName("Several forged X-User-Id headers are all replaced by the single token subject")
    void authenticatedRequest_severalForgedUserIdHeaders_onlyTokenSubjectIsForwarded() throws Exception {
        HttpResponse<String> response = send("GET", "/api/trips/t1", TestTokens.token(USER_ID, "PASSENGER"),
                USER_ID_HEADER, FORGED_USER_ID, USER_ID_HEADER, DRIVER_ID, "x-user-id", FORGED_USER_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(onlyBackendRequest().header(USER_ID_HEADER)).containsExactly(USER_ID.toString());
    }

    // ---------------------------------------------------------------- path tricks

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/auth/login/../../trips/t1",
            "/api/auth/login/%2e%2e/%2e%2e/trips/t1",
            "/api/auth/login/..%2f..%2ftrips/t1",
            "/api/auth/login;x=1/../trips/t1",
            "/api/trips/t1;jsessionid=abc",
            "//api/trips/t1",
            "/api//trips/t1",
            "/api/drivers/" + DRIVER_ID + "%2Freserve",
            "/api/drivers/" + DRIVER_ID + "%2freserve"
    })
    @DisplayName("A path that is not in normal form is refused with 400, with or without a token")
    void anyRoute_nonNormalizedPath_returns400AndIsNotForwarded(String path) throws Exception {
        assertThat(send("POST", path, null).statusCode()).isEqualTo(400);
        assertThat(send("POST", path, TestTokens.token(USER_ID, "DRIVER"),
                INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY).statusCode()).isEqualTo(400);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/API/AUTH/LOGIN", "/Api/Auth/Login", "/api/auth/Login", "/api/Auth/register/passenger"})
    @DisplayName("Public paths are matched case-sensitively: a different spelling is not public")
    void publicRoute_differentCase_returns401(String path) throws Exception {
        HttpResponse<String> response = send("POST", path, null);

        assertRejected(response, 401, "MISSING_TOKEN");
    }

    // ---------------------------------------------------------------- internal paths

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,   /internal/drivers",
            "DELETE, /internal/drivers?userId=" + DRIVER_ID,
            "GET,    /internal/drivers/" + DRIVER_ID,
            "POST,   /api/drivers/" + DRIVER_ID + "/reserve",
            "DELETE, /api/drivers/" + DRIVER_ID + "/reserve",
            "POST,   /api/drivers/" + DRIVER_ID + "/reserve/"
    })
    @DisplayName("Service-to-service paths are never forwarded, even with a valid token and the internal API key")
    void internalPath_withTokenAndInternalApiKey_returns404AndIsNotForwarded(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, TestTokens.token(USER_ID, "DRIVER"),
                INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    // ---------------------------------------------------------------- sessions

    @Test
    @DisplayName("The gateway is stateless: no session cookie, and the next request without a token is anonymous again")
    void authenticatedRequest_thenRequestWithoutToken_isNotRemembered() throws Exception {
        HttpResponse<String> authenticated = send("GET", "/api/trips/t1", TestTokens.token(USER_ID, "PASSENGER"));

        assertThat(authenticated.statusCode()).isEqualTo(200);
        assertThat(authenticated.headers().allValues("Set-Cookie")).isEmpty();
        assertRejectedStatus(send("GET", "/api/trips/t1", null), 401, "MISSING_TOKEN");
        assertThat(BACKEND_REQUESTS).hasSize(1);
    }

    // ---------------------------------------------------------------- what responses give away

    @Test
    @DisplayName("A 401 for a bad token does not echo the token or name the library or exception")
    void protectedRoute_tokenSignedWithOtherSecret_errorBodyRevealsNoDetail() throws Exception {
        String token = TestTokens.token(TestTokens.OTHER_SECRET, USER_ID.toString(), "PASSENGER",
                java.time.Instant.now().plusSeconds(3600));

        HttpResponse<String> response = send("GET", "/api/trips/t1", token);

        assertRejected(response, 401, "INVALID_TOKEN");
        assertThat(response.body())
                .doesNotContain(token)
                .doesNotContain(token.split("\\.")[2])
                .doesNotContainIgnoringCase("jsonwebtoken")
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("signature")
                .doesNotContain("\"trace\"");
    }

    @Test
    @DisplayName("A backend that is down gives a 5xx that names no host, port, class or stack trace")
    void routedRequest_backendDown_errorBodyRevealsNoDetail() throws Exception {
        HttpResponse<String> response = send("GET", "/api/payments/t1", TestTokens.token(USER_ID, "PASSENGER"));

        assertThat(response.statusCode()).isBetween(500, 599);
        assertThat(response.body())
                .doesNotContain("localhost")
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("refused")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"message\"");
    }

    @Test
    @DisplayName("The error page itself cannot be opened without a token")
    void errorPath_calledDirectlyWithoutToken_returns401() throws Exception {
        assertRejected(send("GET", "/error", null), 401, "MISSING_TOKEN");
    }

    @Test
    @DisplayName("Responses carry the browser hardening headers, for proxied answers and for the gateway's own errors")
    void anyResponse_carriesSecurityHeaders() throws Exception {
        HttpResponse<String> proxied = send("GET", "/api/trips/t1", TestTokens.token(USER_ID, "PASSENGER"));
        HttpResponse<String> rejected = send("GET", "/api/trips/t1", null);

        for (HttpResponse<String> response : List.of(proxied, rejected)) {
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
    }

    @Test
    @DisplayName("No cross-origin access is granted: a preflight is refused and answers carry no CORS header")
    void crossOriginRequest_isNotGrantedAccess() throws Exception {
        HttpResponse<String> preflight = send("OPTIONS", "/api/trips", null,
                "Origin", "http://evil.example", "Access-Control-Request-Method", "POST");
        HttpResponse<String> simple = send("GET", "/api/trips/t1", TestTokens.token(USER_ID, "PASSENGER"),
                "Origin", "http://evil.example");

        assertThat(preflight.statusCode()).isEqualTo(401);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(simple.statusCode()).isEqualTo(200);
        assertThat(simple.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ---------------------------------------------------------------- management and documentation endpoints

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /actuator/env",
            "GET,  /actuator/beans",
            "GET,  /actuator/configprops",
            "GET,  /actuator/heapdump",
            "GET,  /actuator/threaddump",
            "GET,  /actuator/mappings",
            "GET,  /actuator/loggers",
            "POST, /actuator/loggers/ROOT",
            "POST, /actuator/shutdown",
            "GET,  /actuator/gateway/routes"
    })
    @DisplayName("Only health, info and prometheus are exposed by the actuator")
    void actuator_sensitiveEndpoint_returns404(String method, String path) throws Exception {
        assertThat(send(method, path, null).statusCode()).isEqualTo(404);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/"})
    @DisplayName("The gateway serves no API documentation or other page to anonymous callers")
    void documentationPath_withoutToken_returns401(String path) throws Exception {
        assertRejected(send("GET", path, null), 401, "MISSING_TOKEN");
    }

    // ---------------------------------------------------------------- helpers

    private void assertRejected(HttpResponse<String> response, int status, String code) {
        assertRejectedStatus(response, status, code);
        assertThat(BACKEND_REQUESTS).isEmpty();
    }

    private void assertRejectedStatus(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private BackendRequest onlyBackendRequest() {
        assertThat(BACKEND_REQUESTS).hasSize(1);
        return BACKEND_REQUESTS.get(0);
    }

    private static String claims(String role) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"sub\":\"%s\",\"role\":\"%s\",\"iat\":%d,\"exp\":%d}".formatted(USER_ID, role, now - 60, now + 3600);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // headers: name, value, name, value, ...
    private HttpResponse<String> send(String method, String path, String token, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", TestTokens.bearer(token));
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                BACKEND_REQUESTS.add(new BackendRequest(exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders())));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

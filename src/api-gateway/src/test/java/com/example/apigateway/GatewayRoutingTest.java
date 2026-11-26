package com.example.apigateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks which paths the gateway routes (docs/api.md §10). Every backend points at a closed port, so a routed
 * request fails with 5xx while a path without a route gets the gateway's own 404.
 * Protected routes are called with a token of a role the route allows, so the security layer lets them through.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=" + TestTokens.TEST_SECRET,
                "IDENTITY_SERVICE_URL=http://localhost:1",
                "DRIVER_SERVICE_URL=http://localhost:1",
                "TRIP_SERVICE_URL=http://localhost:1",
                "MATCHING_SERVICE_URL=http://localhost:1",
                "PRICING_SERVICE_URL=http://localhost:1",
                "PAYMENT_SERVICE_URL=http://localhost:1",
                "NOTIFICATION_SERVICE_URL=http://localhost:1"
        })
class GatewayRoutingTest {

    private static final String NO_TOKEN = "NONE";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${local.server.port}")
    private int port;

    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource({
            "POST,   /api/auth/login,              NONE",
            "POST,   /api/auth/register/passenger, NONE",
            "POST,   /api/auth/register/driver,    NONE",
            "GET,    /api/users/u1,                PASSENGER",
            "GET,    /api/drivers/d1,              DRIVER",
            "GET,    /api/drivers/available,       NONE",
            "GET,    /api/drivers/nearby,          PASSENGER",
            "PATCH,  /api/drivers/d1/status,       DRIVER",
            "PATCH,  /api/drivers/d1/location,     DRIVER",
            "POST,   /api/trips,                   PASSENGER",
            "GET,    /api/trips/t1,                PASSENGER",
            "POST,   /api/trips/t1/accept,         DRIVER",
            "POST,   /api/matching/r1/match,       ADMIN",
            "POST,   /api/pricing/quote,           PASSENGER",
            "GET,    /api/payments/t1,             PASSENGER",
            "POST,   /api/payments/t1/checkout,    PASSENGER",
            "POST,   /api/payments/t1/pay,         PASSENGER",
            "GET,    /api/payments/t1/checkout,    PASSENGER",
            "GET,    /api/payments/t1/checkout,    DRIVER",
            "POST,   /api/payments/webhooks/payos, NONE",
            "GET,    /api/notifications,           PASSENGER"
    })
    void route_publicPath_forwardedToBackend(String method, String path, String role) throws Exception {
        assertThat(send(method, path, role)).isNotIn(401, 403, 404);
    }

    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource({
            "POST,   /api/drivers/d1/reserve, NONE",
            "DELETE, /api/drivers/d1/reserve, NONE",
            "POST,   /api/drivers/d1/reserve, DRIVER",
            "DELETE, /api/drivers/d1/reserve, PASSENGER",
            "POST,   /internal/drivers,       NONE",
            "DELETE, /internal/drivers,       NONE",
            "POST,   /api/matching/r1/rematch, NONE",
            "POST,   /api/matching/r1/rematch, ADMIN",
            "POST,   /api/pricing/final,       NONE",
            "POST,   /api/pricing/final,       DRIVER",
            "POST,   /api/payments/t1/charge,  NONE",
            "POST,   /api/payments/t1/charge,  PASSENGER",
            "POST,   /api/payments/t1/refund,  NONE",
            "POST,   /api/payments/t1/refund,  ADMIN",
            "GET,    /api/unknown,            PASSENGER"
    })
    void route_internalOrUnknownPath_returns404(String method, String path, String role) throws Exception {
        assertThat(send(method, path, role)).isEqualTo(404);
    }

    @Test
    void route_unknownPathWithoutToken_returns401() throws Exception {
        assertThat(send("GET", "/api/unknown", NO_TOKEN)).isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/payments/t1/checkout, DRIVER, 403",
            "POST, /api/payments/t1/pay, DRIVER, 403",
            "POST, /api/payments/t1/pay, NONE, 401",
            "POST, /api/payments/t1/checkout, ADMIN, 403",
            "POST, /api/payments/t1/checkout, NONE, 401",
            "GET, /api/payments/t1/checkout, NONE, 401",
            "GET, /api/payments/webhooks/payos, NONE, 401",
            "POST, /api/payments/webhooks/other, NONE, 401"
    })
    void checkoutAndWebhook_haveExactAccessRules(String method, String path, String role, int status) throws Exception {
        assertThat(send(method, path, role)).isEqualTo(status);
    }

    // role NONE sends no token
    private int send(String method, String path, String role) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (!NO_TOKEN.equals(role)) {
            builder.header("Authorization", TestTokens.bearer(TestTokens.token(UUID.randomUUID(), role)));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

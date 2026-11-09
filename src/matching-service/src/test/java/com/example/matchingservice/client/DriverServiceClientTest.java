package com.example.matchingservice.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import org.springframework.web.client.RestClientException;
import static org.junit.jupiter.api.Assertions.*;

class DriverServiceClientTest {

    @Test
    void reservationAndReleaseCarryInternalIdentityCorrelationAndTripGuard() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, String>>();
        var trip = java.util.UUID.randomUUID();
        var driver = java.util.UUID.randomUUID();
        String correlation = java.util.UUID.randomUUID().toString();
        server.createContext("/api/drivers", e -> {
            calls.add(java.util.Map.of("method", e.getRequestMethod(), "query",
                    java.util.Objects.toString(e.getRequestURI().getQuery(), ""), "body",
                    new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "key",
                    java.util.Objects.toString(e.getRequestHeaders().getFirst("X-Internal-Api-Key"), ""), "correlation",
                    java.util.Objects.toString(e.getRequestHeaders().getFirst("X-Correlation-ID"), "")));
            if ("DELETE".equals(e.getRequestMethod())) {
                e.sendResponseHeaders(204, -1);
                e.close();
                return;
            }
            String body = "GET".equals(e.getRequestMethod()) ? "[]"
                    : "{\"driverId\":\"" + driver + "\",\"status\":\"RESERVED\",\"reservedForTrip\":\"" + trip
                            + "\",\"reservedUntil\":\"2026-11-09T12:00:30Z\",\"version\":4}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
        });
        server.start();
        org.slf4j.MDC.put("correlationId", correlation);
        try {
            var builder = org.springframework.web.client.RestClient.builder()
                .requestInterceptor(new com.example.matchingservice.config.RestClientCorrelationInterceptor());
            var client = new DriverServiceClient(builder, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key");
            assertTrue(client.getAvailableDrivers(10.77, 106.7, 5.0).isEmpty());
            assertEquals(trip, client.reserveDriver(driver, trip, 3).getReservedForTrip());
            client.releaseDriverReservation(driver, trip);
            assertEquals(3, calls.size());
            for (var call : calls) {
                assertEquals("test-key", call.get("key"));
                assertEquals(correlation, call.get("correlation"));
            }
            var body = new tools.jackson.databind.ObjectMapper().readTree(calls.get(1).get("body"));
            assertEquals(trip.toString(), body.get("tripId").asString());
            assertEquals(3, body.get("expectedVersion").asInt());
            assertEquals("tripId=" + trip, calls.get(2).get("query"));
        }
        finally {
            org.slf4j.MDC.remove("correlationId");
            server.stop(0);
        }
    }

    @Test
    void downstreamFailureIsNotReportedAsNoDrivers() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/drivers/available", e -> {
            e.sendResponseHeaders(503, -1);
            e.close();
        });
        server.start();
        try {
            var client = new DriverServiceClient(org.springframework.web.client.RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            assertThrows(RestClientException.class, () -> client.getAvailableDrivers(21.0, 105.0, 5.0));
        }
        finally {
            server.stop(0);
        }
    }

}

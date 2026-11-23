package com.example.pricingservice;

import com.example.pricingservice.client.RoutingClient;
import com.example.pricingservice.dto.request.LocationDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real HTTP call and JSON parsing against a local stand-in for the OSRM server. */
class RoutingClientTest {

    private static final LocationDto FROM = new LocationDto(21.0242, 105.8576, "Nha hat Lon");
    private static final LocationDto TO = new LocationDto(21.0293, 105.8355, "Van Mieu");

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RoutingClient client() {
        return new RoutingClient("http://127.0.0.1:" + server.getAddress().getPort(), 2000, new JsonMapper());
    }

    @Test
    @DisplayName("drivingRoute reads the road distance and turns GeoJSON [lng, lat] into [lat, lng] points, once per pair")
    void drivingRoute_okResponse_returnsRoadAndCachesIt() {
        answer.set("""
                {"code":"Ok","routes":[{"distance":2980.0,"duration":308.1,
                 "geometry":{"type":"LineString","coordinates":[[105.8576,21.0242],[105.845,21.026],[105.8355,21.0293]]}}]}
                """);
        RoutingClient client = client();

        RoutingClient.Route route = client.drivingRoute(FROM, TO).orElseThrow();
        client.drivingRoute(FROM, TO);

        assertEquals(2.98, route.distanceKm(), 1e-9);
        assertEquals(List.of(List.of(21.0242, 105.8576), List.of(21.026, 105.845), List.of(21.0293, 105.8355)), route.points());
        assertEquals("/route/v1/driving/105.8576,21.0242;105.8355,21.0293", path.get());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("drivingRoute is empty when the server finds no route, so pricing falls back")
    void drivingRoute_noRoute_isEmpty() {
        answer.set("{\"code\":\"NoRoute\",\"routes\":[]}");
        assertTrue(client().drivingRoute(FROM, TO).isEmpty());
    }

    @Test
    @DisplayName("drivingRoute is empty when the server cannot be reached")
    void drivingRoute_serverDown_isEmpty() {
        server.stop(0);
        assertTrue(client().drivingRoute(FROM, TO).isEmpty());
    }
}

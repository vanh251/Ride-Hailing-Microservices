package com.example.tripservice.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.*;
import com.example.tripservice.dto.LocationDto;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PricingServiceClientTest {

    @Test
    void quoteUsesTotalFare_andFinalFailureDoesNotReturnFallback() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pricing/quote", e -> {
            e.getRequestBody().readAllBytes();
            byte[] b = "{\"estimatedDistanceKm\":2.5,\"estimatedDurationMin\":12,\"totalFare\":33500,\"currency\":\"VND\"}"
                .getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.createContext("/api/pricing/final", e -> {
            e.sendResponseHeaders(503, -1);
            e.close();
        });
        server.start();
        try {
            var client = new PricingServiceClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var p = LocationDto.builder().latitude(21.0).longitude(105.0).build();
            assertEquals(new BigDecimal("33500"), client.getQuote(p, p).totalFare());
            assertThrows(RestClientException.class, () -> client.getFinalFare(UUID.randomUUID(), 2.5, 12));
        }
        finally {
            server.stop(0);
        }
    }

}

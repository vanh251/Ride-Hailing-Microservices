package com.example.matchingservice.client;

import org.springframework.beans.factory.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

@Component
public class TripServiceClient {

    private final RestClient client;

    public TripServiceClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
            @Value("${trip-service.url:http://trip-service:8083}") String url,
            @Value("${internal.api-key:}") String key) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        client = builder.clone().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Api-Key", key).build();
    }

    public String getTripStatus(UUID tripId) {
        Map<?, ?> response = client.get().uri("/api/trips/{id}", tripId).retrieve().body(Map.class);
        if (response == null || response.get("status") == null)
            throw new RestClientException("Missing trip status");
        return response.get("status").toString();
    }

    public void cancelForMatchingTimeout(UUID tripId, String correlationId) {
        client.post()
            .uri("/api/trips/{id}/cancel", tripId)
            .header("X-Correlation-ID", correlationId)
            .body(Map.of("reason", "MATCHING_TIMEOUT"))
            .retrieve()
            .toBodilessEntity();
    }

}

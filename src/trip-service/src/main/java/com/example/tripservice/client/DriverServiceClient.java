package com.example.tripservice.client;

import org.springframework.beans.factory.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.UUID;

@Component
public class DriverServiceClient {

    private final RestClient client;

    public DriverServiceClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
            @Value("${driver-service.url:http://driver-service:8082}") String url,
            @Value("${internal.api-key:}") String key) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        client = builder.clone().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Api-Key", key).build();
    }

    public void releaseDriverReservation(UUID driverId, UUID tripId) {
        client.delete()
            .uri("/api/drivers/{id}/reserve?tripId={tripId}", driverId, tripId)
            .retrieve()
            .toBodilessEntity();
    }

}

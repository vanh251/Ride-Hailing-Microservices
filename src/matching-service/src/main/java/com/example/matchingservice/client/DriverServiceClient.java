package com.example.matchingservice.client;

import com.example.matchingservice.dto.*;
import com.example.matchingservice.exception.DriverReservationConflictException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

@Component
public class DriverServiceClient {

    private final RestClient client;

    @Autowired
    public DriverServiceClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
            @Value("${driver-service.url:http://driver-service:8082}") String url,
            @Value("${internal.api-key:}") String key) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        client = builder.clone().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Api-Key", key).build();
    }

    public List<AvailableDriverDto> getAvailableDrivers(Double lat, Double lng, Double radiusKm) {
        var response = client.get()
            .uri(b -> b.path("/api/drivers/available")
                .queryParam("latitude", lat)
                .queryParam("longitude", lng)
                .queryParam("radiusKm", radiusKm)
                .build())
            .retrieve()
            .body(new ParameterizedTypeReference<List<AvailableDriverDto>>() {
            });
        if (response == null)
            throw new RestClientException("Driver returned an empty response");
        return response;
    }

    public ReserveDriverResponse reserveDriver(UUID driverId, UUID tripId, Integer version) {
        return client.post()
            .uri("/api/drivers/{id}/reserve", driverId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ReserveDriverRequest.builder().tripId(tripId).expectedVersion(version).build())
            .retrieve()
            .onStatus(s -> s.value() == 409, (req, res) -> {
                throw new DriverReservationConflictException(driverId);
            })
            .body(ReserveDriverResponse.class);
    }

    public void releaseDriverReservation(UUID driverId, UUID tripId) {
        client.delete()
            .uri("/api/drivers/{id}/reserve?tripId={tripId}", driverId, tripId)
            .retrieve()
            .toBodilessEntity();
    }

}

package com.example.paymentservice.payos;

import com.example.paymentservice.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.math.BigDecimal;
import java.util.UUID;

@Component
public class TripPaymentClient {
    public record Trip(UUID tripId, UUID passengerId, UUID driverId, String status,
            BigDecimal finalFare, String currency, String paymentProvider) {}
    private final RestClient client;
    public TripPaymentClient(@Value("${trip-service.url:http://trip-service:8083}") String url,
            @Value("${internal.api-key:local-dev-internal-api-key}") String key) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Api-Key",key).build();
    }
    public Trip get(UUID tripId) {
        try {
            Trip trip=client.get().uri("/api/trips/{id}",tripId).retrieve().body(Trip.class);
            if (trip == null || !tripId.equals(trip.tripId()) || trip.passengerId()==null)
                throw new IllegalStateException("Invalid canonical trip");
            return trip;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value()==404) throw new BusinessException("Trip not found",HttpStatus.NOT_FOUND,"TRIP_NOT_FOUND");
            throw new BusinessException("Trip Service unavailable",HttpStatus.SERVICE_UNAVAILABLE,"TRIP_UNAVAILABLE");
        } catch (Exception e) {
            throw new BusinessException("Trip Service unavailable",HttpStatus.SERVICE_UNAVAILABLE,"TRIP_UNAVAILABLE");
        }
    }
}

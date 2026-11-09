package com.example.tripservice.client;

import com.example.tripservice.dto.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

@Component
public class PricingServiceClient {

    private final RestClient client;

    public PricingServiceClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
            @Value("${pricing-service.url:http://pricing-service:8085}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        client = builder.clone().baseUrl(url).requestFactory(factory).build();
    }

    public PricingQuoteResponse getQuote(LocationDto pickup, LocationDto destination) {
        var response = client.post()
            .uri("/api/pricing/quote")
            .body(Map.of("pickup", Map.of("latitude", pickup.getLatitude(), "longitude", pickup.getLongitude()),
                    "destination",
                    Map.of("latitude", destination.getLatitude(), "longitude", destination.getLongitude())))
            .retrieve()
            .body(PricingQuoteResponse.class);
        if (response == null || response.estimatedDistanceKm() == null
                || !Double.isFinite(response.estimatedDistanceKm()) || response.estimatedDistanceKm() < 0
                || response.estimatedDurationMin() == null || response.estimatedDurationMin() < 0)
            throw new RestClientException("Invalid Pricing quote");
        validateFare(response.totalFare(), response.currency());
        return response;
    }

    public PricingFinalFareResponse getFinalFare(UUID tripId, double distanceKm, int durationMin) {
        var response = client.post()
            .uri("/api/pricing/final")
            .body(Map.of("tripId", tripId, "actualDistanceKm", distanceKm, "actualDurationMin", durationMin))
            .retrieve()
            .body(PricingFinalFareResponse.class);
        if (response == null || !tripId.equals(response.tripId()))
            throw new RestClientException("Invalid Pricing final response");
        validateFare(response.totalFare(), response.currency());
        return response;
    }

    private static void validateFare(java.math.BigDecimal fare, String currency) {
        if (fare == null || fare.signum() <= 0 || !"VND".equals(currency))
            throw new RestClientException("Invalid Pricing fare/currency");
    }

}

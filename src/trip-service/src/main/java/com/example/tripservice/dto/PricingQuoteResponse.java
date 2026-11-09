package com.example.tripservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingQuoteResponse(Double estimatedDistanceKm, Integer estimatedDurationMin, BigDecimal totalFare,
        String currency) {
}

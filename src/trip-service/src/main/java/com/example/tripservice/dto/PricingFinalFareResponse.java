package com.example.tripservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingFinalFareResponse(UUID tripId, BigDecimal totalFare, String currency) {
}

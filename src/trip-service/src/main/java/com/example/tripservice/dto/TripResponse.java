package com.example.tripservice.dto;

import com.example.tripservice.entity.TripStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Trip response containing complete trip state and metadata")
public class TripResponse {

    @Schema(description = "Unique ID of the trip", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID tripId;

    @Schema(description = "Current lifecycle status of the trip", example = "SEARCHING")
    private TripStatus status;

    @Schema(description = "ID of passenger who requested the trip", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
    private UUID passengerId;

    @Schema(description = "ID of assigned driver (null until matched)", example = "d1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
    private UUID driverId;

    @Schema(description = "Pickup location details")
    private LocationDto pickup;

    @Schema(description = "Destination location details")
    private LocationDto destination;

    @Schema(description = "Estimated fare in VND", example = "45000")
    private BigDecimal estimatedFare;

    @Schema(description = "Final calculated fare upon completion in VND", example = "47500")
    private BigDecimal finalFare;

    @Schema(description = "Payment provider", allowableValues = {"MOCK", "PAYOS"})
    private String paymentProvider;

    @Schema(description = "Fare currency", example = "VND")
    private String currency;

    @Schema(description = "Distributed trace correlation ID", example = "corr-550e8400-e29b-41d4-a716-446655440000")
    private String correlationId;

    @Schema(description = "Timestamp when trip was created")
    private Instant createdAt;

    @Schema(description = "Timestamp when trip was last updated")
    private Instant updatedAt;

    @Schema(description = "Timestamp when driver explicitly accepted the trip; absent while awaiting acceptance")
    private Instant acceptedAt;

    @Schema(description = "Timestamp when driver started trip")
    private Instant startedAt;

    @Schema(description = "Timestamp when driver completed trip")
    private Instant completedAt;

    @Schema(description = "Timestamp when trip was cancelled")
    private Instant cancelledAt;
}

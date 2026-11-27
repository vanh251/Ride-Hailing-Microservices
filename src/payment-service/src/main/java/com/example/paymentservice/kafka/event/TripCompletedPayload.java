package com.example.paymentservice.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

// Payload of TripCompleted (docs/events.md §4.5). Ids stay strings here and are validated by the consumer.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TripCompletedPayload {
    private String tripId;
    private String driverId;
    private String passengerId;
    private Double actualDistanceKm;
    private Integer actualDurationMin;
    private BigDecimal finalFare;
    private String currency;
    private Instant completedAt;
}

package com.example.tripservice.service;

import com.example.tripservice.dto.*;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

public interface TripService {

    TripResponse createTrip(CreateTripRequest request, String correlationId, UUID passengerId);

    TripResponse getTrip(UUID tripId);

    TripResponse acceptTrip(UUID tripId, UUID driverId);

    TripResponse startTrip(UUID tripId, UUID driverId);

    TripResponse completeTrip(UUID tripId, UUID driverId);

    TripResponse cancelTrip(UUID tripId, CancelTripRequest request, UUID passengerId);

    TripResponse cancelForMatchingTimeout(UUID tripId);

    TripResponse beginAssignment(UUID tripId, UUID driverId, int reservationVersion, Instant reservedUntil,
            UUID sourceEventId);

    void applyPaymentResult(UUID tripId, UUID paymentId, UUID passengerId, BigDecimal amount, String currency,
            boolean succeeded, String reason);

}

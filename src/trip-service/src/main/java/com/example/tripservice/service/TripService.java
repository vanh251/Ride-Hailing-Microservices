package com.example.tripservice.service;

import com.example.tripservice.dto.*;
import java.util.UUID;
import java.math.BigDecimal;

public interface TripService {

    TripResponse createTrip(CreateTripRequest request, String correlationId, UUID passengerId);

    TripResponse getTrip(UUID tripId);

    TripResponse acceptTrip(UUID tripId, UUID driverId);

    TripResponse startTrip(UUID tripId, UUID driverId);

    TripResponse completeTrip(UUID tripId, UUID driverId);

    TripResponse cancelTrip(UUID tripId, CancelTripRequest request, UUID passengerId);

    TripResponse cancelForMatchingTimeout(UUID tripId);

    TripResponse assignDriver(UUID tripId, UUID driverId, UUID sourceEventId);

    void applyPaymentResult(UUID tripId, UUID paymentId, UUID passengerId, BigDecimal amount, String currency,
            boolean succeeded, String reason);

}

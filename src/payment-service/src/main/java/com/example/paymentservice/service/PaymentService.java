package com.example.paymentservice.service;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.dto.response.PaymentResponse;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse charge(UUID tripId, PaymentChargeRequest request, String idempotencyKeyHeader, String correlationId);

    PaymentResponse getPaymentByTripId(UUID tripId);

    PaymentResponse refund(UUID tripId, String correlationId);

    /** The passenger's simulated transfer for a completed trip; repeating it returns the first result. */
    PaymentResponse payTrip(UUID tripId, UUID passengerId);

    void processTripCompleted(String eventId, UUID tripId, UUID passengerId, BigDecimal finalFare,
                              String currency, String correlationId);
}

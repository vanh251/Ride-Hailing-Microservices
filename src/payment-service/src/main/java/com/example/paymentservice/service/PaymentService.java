package com.example.paymentservice.service;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.dto.response.PaymentResponse;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse charge(UUID tripId, PaymentChargeRequest request, String idempotencyKeyHeader, String correlationId);

    PaymentResponse getPaymentByTripId(UUID tripId);

    PaymentResponse refund(UUID tripId, String correlationId);

    void processPayosTripCompleted(UUID tripId);

    void processTripCompleted(String eventId, UUID tripId, UUID passengerId, BigDecimal finalFare,
                              String currency, String correlationId);
}

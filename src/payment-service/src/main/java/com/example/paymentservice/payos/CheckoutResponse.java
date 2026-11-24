package com.example.paymentservice.payos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CheckoutResponse(UUID tripId, UUID paymentId, String status, BigDecimal amount,
        String currency, String checkoutUrl, Instant expiresAt, String provider, String message) {}

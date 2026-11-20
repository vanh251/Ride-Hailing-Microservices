package com.example.driverservice.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Envelope of every domain event (docs/events.md §2). There is no shared library: each service keeps its own copy,
 * with the same fields as the copies in trip-service and payment-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {

    private String eventId;
    private String eventType;
    private String aggregateId;
    private String aggregateType;
    private String producer;
    private String correlationId;
    private String causationId;
    @Builder.Default
    private String schemaVersion = "1.0";
    private Instant occurredAt;
    private T payload;
}

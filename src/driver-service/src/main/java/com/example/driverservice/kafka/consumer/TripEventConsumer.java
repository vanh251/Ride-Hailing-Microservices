package com.example.driverservice.kafka.consumer;

import com.example.driverservice.exception.InvalidEventException;
import com.example.driverservice.service.DriverTripEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

/**
 * Consumes DriverAssigned, TripCompleted and TripCancelled from trip-events and moves the driver between
 * RESERVED, BUSY and AVAILABLE (docs/events.md §4.3, §4.5, §4.8, §5). Other event types on the topic are
 * acknowledged and ignored. Failures are not caught here: the error handler in KafkaConsumerConfig retries
 * them and then moves the record to trip-events.DLQ. A DriverAssigned without a valid reservationVersion is
 * malformed and goes to the DLQ without retry; one whose reservation no longer matches is not an error, it is
 * answered with DriverAssignmentRejected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEventConsumer {

    public static final String TRIP_EVENTS_TOPIC = "trip-events";
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final Set<String> CONSUMED_EVENT_TYPES = Set.of(DriverTripEventService.DRIVER_ASSIGNED,
            DriverTripEventService.TRIP_COMPLETED, DriverTripEventService.TRIP_CANCELLED);

    private final DriverTripEventService driverTripEventService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TRIP_EVENTS_TOPIC, groupId = "${spring.kafka.consumer.group-id:driver-service-group}")
    public void consumeTripEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        JsonNode root = parse(record.value());
        String eventType = root.path("eventType").asText("");
        if (!CONSUMED_EVENT_TYPES.contains(eventType)) {
            acknowledgment.acknowledge();
            return;
        }

        UUID eventId = requireUuid(root, "eventId", eventType + " eventId");
        String correlationId = root.path(CORRELATION_ID_KEY).asText("");
        if (!correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_KEY, correlationId);
        }
        try {
            JsonNode payload = root.path("payload");
            UUID tripId = requireUuid(payload, "tripId", eventType + " payload.tripId");
            log.info("Processing event: eventType={}, eventId={}, tripId={}, partition={}, offset={}",
                    eventType, eventId, tripId, record.partition(), record.offset());

            switch (eventType) {
                case DriverTripEventService.DRIVER_ASSIGNED -> driverTripEventService.onDriverAssigned(
                        eventId, correlationId.isBlank() ? null : correlationId, tripId,
                        requireUuid(payload, "driverId", eventType + " payload.driverId"),
                        optionalUuid(payload, "passengerId", eventType + " payload.passengerId"),
                        requireReservationVersion(payload, eventType + " payload.reservationVersion"));
                case DriverTripEventService.TRIP_COMPLETED -> driverTripEventService.onTripCompleted(eventId, tripId);
                // The driver is found through drivers.reserved_for_trip: trip-service sends no driverId here
                default -> driverTripEventService.onTripCancelled(eventId, tripId);
            }
            acknowledgment.acknowledge();
        } finally {
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    private JsonNode parse(String message) {
        if (message == null || message.isBlank()) {
            throw new InvalidEventException("Empty message on " + TRIP_EVENTS_TOPIC);
        }
        try {
            return objectMapper.readTree(message);
        } catch (JacksonException e) {
            throw new InvalidEventException("Message on " + TRIP_EVENTS_TOPIC + " is not valid JSON", e);
        }
    }

    private static UUID requireUuid(JsonNode node, String field, String description) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new InvalidEventException(description + " is missing");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(description + " is not a UUID: " + value, e);
        }
    }

    // Only copied into the result for notification-service, so a missing or unreadable value is not an error
    private static UUID optionalUuid(JsonNode node, String field, String description) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("{} is not a UUID and is left out of the result: {}", description, value);
            return null;
        }
    }

    // The driver version the reservation produced (docs/events.md §4.2): a whole number of at least 1
    private static int requireReservationVersion(JsonNode node, String description) {
        JsonNode value = node.path("reservationVersion");
        if (value.isMissingNode() || value.isNull()) {
            throw new InvalidEventException(description + " is missing");
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new InvalidEventException(description + " is not a whole number of at least 1: " + value);
        }
        return value.intValue();
    }
}

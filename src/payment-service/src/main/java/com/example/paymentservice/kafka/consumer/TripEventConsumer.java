package com.example.paymentservice.kafka.consumer;

import com.example.paymentservice.exception.ValidationException;
import com.example.paymentservice.kafka.event.TripCompletedPayload;
import com.example.paymentservice.service.PaymentService;
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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumes TripCompleted from trip-events and starts the charge (docs/events.md §4.5, §9 step 11).
 * Failures are not caught here: the error handler in KafkaConsumerConfig retries them and then
 * moves the record to trip-events.DLQ. Other event types on the topic are acknowledged and ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEventConsumer {

    public static final String TRIP_EVENTS_TOPIC = "trip-events";
    public static final String TRIP_COMPLETED = "TripCompleted";
    private static final String CORRELATION_ID_KEY = "correlationId";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TRIP_EVENTS_TOPIC, groupId = "${spring.kafka.consumer.group-id:payment-service-group}")
    public void consumeTripEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        JsonNode root = parse(record.value());
        String eventType = root.path("eventType").asText("");
        if (!TRIP_COMPLETED.equals(eventType)) {
            acknowledgment.acknowledge();
            return;
        }

        String eventId = requireText(root, "eventId");
        String correlationId = requireText(root, CORRELATION_ID_KEY);
        MDC.put(CORRELATION_ID_KEY, correlationId);
        try {
            TripCompletedPayload payload = objectMapper.treeToValue(root.path("payload"), TripCompletedPayload.class);
            UUID tripId = requireUuid(payload.getTripId(), "payload.tripId");
            UUID passengerId = requireUuid(payload.getPassengerId(), "payload.passengerId");
            BigDecimal finalFare = payload.getFinalFare();
            if (finalFare == null || finalFare.signum() <= 0) {
                throw new ValidationException("TripCompleted payload.finalFare must be a positive amount");
            }

            log.info("Processing event: eventType={}, eventId={}, tripId={}, partition={}, offset={}",
                    eventType, eventId, tripId, record.partition(), record.offset());
            paymentService.processTripCompleted(eventId, tripId, passengerId, finalFare,
                    payload.getCurrency(), correlationId);
            acknowledgment.acknowledge();
        } finally {
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    private JsonNode parse(String message) {
        if (message == null || message.isBlank()) {
            throw new ValidationException("Empty message on " + TRIP_EVENTS_TOPIC);
        }
        try {
            return objectMapper.readTree(message);
        } catch (JacksonException e) {
            throw new ValidationException("Message on " + TRIP_EVENTS_TOPIC + " is not valid JSON", e);
        }
    }

    private static String requireText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) {
            throw new ValidationException("TripCompleted envelope is missing " + field);
        }
        return value;
    }

    private static UUID requireUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("TripCompleted is missing " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("TripCompleted " + field + " is not a UUID: " + value, e);
        }
    }
}

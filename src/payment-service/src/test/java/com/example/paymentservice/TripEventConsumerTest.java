package com.example.paymentservice;

import com.example.paymentservice.exception.ValidationException;
import com.example.paymentservice.kafka.consumer.TripEventConsumer;
import com.example.paymentservice.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripEventConsumerTest {

    private static final String TRIP_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PASSENGER_ID = "550e8400-e29b-41d4-a716-446655440001";

    @Mock
    private PaymentService paymentService;

    @Mock
    private Acknowledgment acknowledgment;

    private TripEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TripEventConsumer(paymentService, new JsonMapper());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TripEventConsumer.TRIP_EVENTS_TOPIC, 0, 0L, TRIP_ID, value);
    }

    private static String tripCompleted(String payload) {
        return """
                {
                  "eventId": "evt-005",
                  "eventType": "TripCompleted",
                  "aggregateId": "%s",
                  "correlationId": "corr-001",
                  "payload": %s
                }
                """.formatted(TRIP_ID, payload);
    }

    @Test
    @DisplayName("consumeTripEvent with a valid TripCompleted charges the trip and acknowledges")
    void consumeTripEvent_validTripCompleted_processesAndAcks() {
        String payload = """
                {"tripId": "%s", "passengerId": "%s", "finalFare": 47500, "currency": "VND", "unknownField": 1}
                """.formatted(TRIP_ID, PASSENGER_ID);

        consumer.consumeTripEvent(record(tripCompleted(payload)), acknowledgment);

        verify(paymentService).processTripCompleted(eq("evt-005"), eq(UUID.fromString(TRIP_ID)),
                eq(UUID.fromString(PASSENGER_ID)), eq(new BigDecimal("47500")), eq("VND"), eq("corr-001"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeTripEvent ignores other event types and acknowledges them")
    void consumeTripEvent_otherEventType_ackedAndIgnored() {
        consumer.consumeTripEvent(record("{\"eventId\": \"evt-004\", \"eventType\": \"TripStarted\"}"), acknowledgment);

        verifyNoInteractions(paymentService);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeTripEvent without passengerId throws ValidationException instead of charging an empty passenger")
    void consumeTripEvent_missingPassengerId_throwsValidationException() {
        String payload = "{\"tripId\": \"%s\", \"finalFare\": 47500}".formatted(TRIP_ID);

        assertThrows(ValidationException.class, () ->
                consumer.consumeTripEvent(record(tripCompleted(payload)), acknowledgment));

        verifyNoInteractions(paymentService);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("consumeTripEvent without finalFare throws ValidationException instead of charging 0")
    void consumeTripEvent_missingFinalFare_throwsValidationException() {
        String payload = "{\"tripId\": \"%s\", \"passengerId\": \"%s\"}".formatted(TRIP_ID, PASSENGER_ID);

        assertThrows(ValidationException.class, () ->
                consumer.consumeTripEvent(record(tripCompleted(payload)), acknowledgment));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("consumeTripEvent with invalid JSON throws ValidationException")
    void consumeTripEvent_invalidJson_throwsValidationException() {
        assertThrows(ValidationException.class, () -> consumer.consumeTripEvent(record("not-json"), acknowledgment));
    }

    @Test
    @DisplayName("consumeTripEvent lets a processing failure propagate so the error handler can retry it")
    void consumeTripEvent_processingFailure_propagates() {
        String payload = "{\"tripId\": \"%s\", \"passengerId\": \"%s\", \"finalFare\": 47500}".formatted(TRIP_ID, PASSENGER_ID);
        doThrow(new IllegalStateException("database down"))
                .when(paymentService).processTripCompleted(any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () ->
                consumer.consumeTripEvent(record(tripCompleted(payload)), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }
}

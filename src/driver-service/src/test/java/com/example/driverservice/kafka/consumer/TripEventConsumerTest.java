package com.example.driverservice.kafka.consumer;

import com.example.driverservice.exception.InvalidEventException;
import com.example.driverservice.service.DriverTripEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class TripEventConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("e0000000-0000-4000-8000-000000000003");
    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID DRIVER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID PASSENGER_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @Mock
    private DriverTripEventService driverTripEventService;

    @Mock
    private Acknowledgment acknowledgment;

    private TripEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TripEventConsumer(driverTripEventService, new JsonMapper());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TripEventConsumer.TRIP_EVENTS_TOPIC, 0, 0L, TRIP_ID.toString(), value);
    }

    private static String event(String eventType, String payload) {
        return event(EVENT_ID.toString(), eventType, payload);
    }

    private static String event(String eventId, String eventType, String payload) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "aggregateId": "%s",
                  "aggregateType": "Trip",
                  "producer": "trip-service",
                  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "payload": %s
                }
                """.formatted(eventId, eventType, TRIP_ID, payload);
    }

    // The DriverAssigned payload trip-service sends (docs/events.md §4.3)
    private static String driverAssignedPayload(String reservationVersion) {
        return "{\"tripId\": \"%s\", \"driverId\": \"%s\", \"passengerId\": \"%s\", \"assignedAt\": \"2026-09-30T10:00:06Z\", %s \"unknownField\": 1}"
                .formatted(TRIP_ID, DRIVER_ID, PASSENGER_ID, reservationVersion);
    }

    @Test
    @DisplayName("DriverAssigned hands the reservation, passenger and correlation id to the service and acknowledges")
    void consumeTripEvent_driverAssigned_handsReservationToServiceAndAcks() {
        consumer.consumeTripEvent(record(event("DriverAssigned", driverAssignedPayload("\"reservationVersion\": 6,"))),
                acknowledgment);

        verify(driverTripEventService).onDriverAssigned(EVENT_ID, CORRELATION_ID, TRIP_ID, DRIVER_ID, PASSENGER_ID, 6);
        verifyNoMoreInteractions(driverTripEventService);
        verify(acknowledgment).acknowledge();
    }

    @ParameterizedTest(name = "passengerId field: {0}")
    @ValueSource(strings = {"", "\"passengerId\": \"\",", "\"passengerId\": null,", "\"passengerId\": \"passenger-1\","})
    @DisplayName("passengerId is optional: when it is missing or unreadable the service gets null")
    void consumeTripEvent_driverAssignedWithoutValidPassengerId_passesNull(String passengerIdField) {
        String payload = "{\"tripId\": \"%s\", \"driverId\": \"%s\", %s \"reservationVersion\": 6}"
                .formatted(TRIP_ID, DRIVER_ID, passengerIdField);

        consumer.consumeTripEvent(record(event("DriverAssigned", payload)), acknowledgment);

        verify(driverTripEventService).onDriverAssigned(eq(EVENT_ID), eq(CORRELATION_ID), eq(TRIP_ID), eq(DRIVER_ID),
                isNull(), eq(6));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("A DriverAssigned without correlationId is still handled; the service is given null")
    void consumeTripEvent_driverAssignedWithoutCorrelationId_passesNull() {
        String message = "{\"eventId\": \"%s\", \"eventType\": \"DriverAssigned\", \"payload\": %s}"
                .formatted(EVENT_ID, "{\"tripId\": \"%s\", \"driverId\": \"%s\", \"reservationVersion\": 3}"
                        .formatted(TRIP_ID, DRIVER_ID));

        consumer.consumeTripEvent(record(message), acknowledgment);

        verify(driverTripEventService).onDriverAssigned(eq(EVENT_ID), isNull(), eq(TRIP_ID), eq(DRIVER_ID), isNull(), eq(3));
        verify(acknowledgment).acknowledge();
    }

    @ParameterizedTest(name = "reservationVersion field: {0}")
    @ValueSource(strings = {
            "",
            "\"reservationVersion\": null,",
            "\"reservationVersion\": 0,",
            "\"reservationVersion\": -1,",
            "\"reservationVersion\": 1.5,",
            "\"reservationVersion\": \"6\",",
            "\"reservationVersion\": 3000000000,"
    })
    @DisplayName("DriverAssigned needs a reservationVersion that is a whole number of at least 1, else it goes to the DLQ")
    void consumeTripEvent_driverAssignedWithoutValidReservationVersion_throwsInvalidEventException(String versionField) {
        assertThrows(InvalidEventException.class, () -> consumer.consumeTripEvent(
                record(event("DriverAssigned", driverAssignedPayload(versionField))), acknowledgment));

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("TripCompleted releases the trip's driver and acknowledges")
    void consumeTripEvent_tripCompleted_releasesDriverAndAcks() {
        String payload = "{\"tripId\": \"%s\", \"driverId\": \"%s\", \"finalFare\": 47500}".formatted(TRIP_ID, DRIVER_ID);

        consumer.consumeTripEvent(record(event("TripCompleted", payload)), acknowledgment);

        verify(driverTripEventService).onTripCompleted(EVENT_ID, TRIP_ID);
        verifyNoMoreInteractions(driverTripEventService);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("TripCompleted with an empty driverId, as trip-service sends it for a trip without driver, is still handled")
    void consumeTripEvent_tripCompletedWithEmptyDriverId_releasesDriverAndAcks() {
        String payload = "{\"tripId\": \"%s\", \"driverId\": \"\"}".formatted(TRIP_ID);

        consumer.consumeTripEvent(record(event("TripCompleted", payload)), acknowledgment);

        verify(driverTripEventService).onTripCompleted(EVENT_ID, TRIP_ID);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("TripCancelled without driverId releases the trip's driver and acknowledges")
    void consumeTripEvent_tripCancelledWithoutDriverId_releasesDriverAndAcks() {
        String payload = "{\"tripId\": \"%s\", \"reason\": \"PASSENGER_CANCELLED\"}".formatted(TRIP_ID);

        consumer.consumeTripEvent(record(event("TripCancelled", payload)), acknowledgment);

        verify(driverTripEventService).onTripCancelled(EVENT_ID, TRIP_ID);
        verifyNoMoreInteractions(driverTripEventService);
        verify(acknowledgment).acknowledge();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RideRequested", "TripStarted", "SomethingNew"})
    @DisplayName("Other event types on trip-events are acknowledged and ignored")
    void consumeTripEvent_otherEventType_ackedAndIgnored(String eventType) {
        // No eventId: an event this service does not consume is not validated either
        consumer.consumeTripEvent(record("{\"eventType\": \"%s\"}".formatted(eventType)), acknowledgment);

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment).acknowledge();
    }

    @ParameterizedTest(name = "eventId = \"{0}\"")
    @ValueSource(strings = {"", "evt-003", "not-a-uuid"})
    @DisplayName("An eventId that is not a UUID is rejected without retry")
    void consumeTripEvent_eventIdNotUuid_throwsInvalidEventException(String eventId) {
        String payload = "{\"tripId\": \"%s\", \"driverId\": \"%s\"}".formatted(TRIP_ID, DRIVER_ID);

        assertThrows(InvalidEventException.class,
                () -> consumer.consumeTripEvent(record(event(eventId, "DriverAssigned", payload)), acknowledgment));

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment, never()).acknowledge();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"DriverAssigned", "TripCompleted", "TripCancelled"})
    @DisplayName("A consumed event without payload.tripId is rejected")
    void consumeTripEvent_missingTripId_throwsInvalidEventException(String eventType) {
        String payload = "{\"driverId\": \"%s\"}".formatted(DRIVER_ID);

        assertThrows(InvalidEventException.class,
                () -> consumer.consumeTripEvent(record(event(eventType, payload)), acknowledgment));

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("A consumed event without payload is rejected")
    void consumeTripEvent_missingPayload_throwsInvalidEventException() {
        String message = "{\"eventId\": \"%s\", \"eventType\": \"TripCompleted\"}".formatted(EVENT_ID);

        assertThrows(InvalidEventException.class, () -> consumer.consumeTripEvent(record(message), acknowledgment));

        verifyNoInteractions(driverTripEventService);
    }

    @ParameterizedTest(name = "driverId: {0}")
    @ValueSource(strings = {"", "\"driverId\": \"\",", "\"driverId\": \"driver-1\","})
    @DisplayName("DriverAssigned needs a driverId that is a UUID")
    void consumeTripEvent_driverAssignedWithoutValidDriverId_throwsInvalidEventException(String driverIdField) {
        String payload = "{%s \"tripId\": \"%s\"}".formatted(driverIdField, TRIP_ID);

        assertThrows(InvalidEventException.class,
                () -> consumer.consumeTripEvent(record(event("DriverAssigned", payload)), acknowledgment));

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment, never()).acknowledge();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "not-json", "[1, 2"})
    @DisplayName("A message that is empty or not JSON is rejected")
    void consumeTripEvent_emptyOrInvalidJson_throwsInvalidEventException(String message) {
        assertThrows(InvalidEventException.class, () -> consumer.consumeTripEvent(record(message), acknowledgment));

        verifyNoInteractions(driverTripEventService);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("A null message is rejected")
    void consumeTripEvent_nullMessage_throwsInvalidEventException() {
        assertThrows(InvalidEventException.class, () -> consumer.consumeTripEvent(record(null), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("A processing failure propagates without acknowledging, so the error handler can retry it")
    void consumeTripEvent_processingFailure_propagatesWithoutAck() {
        String payload = driverAssignedPayload("\"reservationVersion\": 6,");
        doThrow(new IllegalStateException("database down"))
                .when(driverTripEventService).onDriverAssigned(any(), any(), any(), any(), any(), anyInt());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumeTripEvent(record(event("DriverAssigned", payload)), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }
}

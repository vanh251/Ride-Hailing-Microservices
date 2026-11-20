package com.example.driverservice.service;

import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.OutboxEvent;
import com.example.driverservice.entity.ProcessedEvent;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.OutboxEventRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverTripEventServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("e0000000-0000-4000-8000-000000000003");
    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID DRIVER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID PASSENGER_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final int RESERVATION_VERSION = 6;

    private final JsonMapper jsonMapper = new JsonMapper();

    @Mock
    private DriverRepository driverRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private DriverTripEventService service;

    @BeforeEach
    void setUp() {
        service = new DriverTripEventService(driverRepository, processedEventRepository, outboxEventRepository, jsonMapper);
    }

    private ProcessedEvent savedEvent() {
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(saved.capture());
        return saved.getValue();
    }

    private OutboxEvent queuedResult() {
        ArgumentCaptor<OutboxEvent> queued = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(queued.capture());
        return queued.getValue();
    }

    private void assign(String correlationId, UUID passengerId, int markBusyResult) {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.markBusy(eq(DRIVER_ID), eq(TRIP_ID), eq(RESERVATION_VERSION), any())).thenReturn(markBusyResult);

        service.onDriverAssigned(EVENT_ID, correlationId, TRIP_ID, DRIVER_ID, passengerId, RESERVATION_VERSION);
    }

    @Test
    @DisplayName("onDriverAssigned for the exact live reservation marks the driver BUSY and queues DriverAssignmentConfirmed")
    void onDriverAssigned_reservationMatches_marksBusyAndQueuesConfirmed() {
        assign(CORRELATION_ID, PASSENGER_ID, 1);

        OutboxEvent row = queuedResult();
        assertEquals("DriverAssignmentConfirmed", row.getEventType());
        assertEquals(TRIP_ID.toString(), row.getAggregateId());
        assertEquals("Trip", row.getAggregateType());
        assertNotNull(row.getCreatedAt());

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        assertEquals(row.getId().toString(), envelope.path("eventId").asText());
        assertEquals("DriverAssignmentConfirmed", envelope.path("eventType").asText());
        assertEquals(TRIP_ID.toString(), envelope.path("aggregateId").asText());
        assertEquals("Trip", envelope.path("aggregateType").asText());
        assertEquals("driver-service", envelope.path("producer").asText());
        assertEquals(CORRELATION_ID, envelope.path("correlationId").asText());
        assertEquals(EVENT_ID.toString(), envelope.path("causationId").asText());
        assertEquals("1.0", envelope.path("schemaVersion").asText());
        assertDoesNotThrow(() -> Instant.parse(envelope.path("occurredAt").asText()));

        JsonNode payload = envelope.path("payload");
        assertEquals(TRIP_ID.toString(), payload.path("tripId").asText());
        assertEquals(DRIVER_ID.toString(), payload.path("driverId").asText());
        assertEquals(PASSENGER_ID.toString(), payload.path("passengerId").asText());
        assertEquals(RESERVATION_VERSION, payload.path("reservationVersion").intValue());
        assertEquals(EVENT_ID.toString(), payload.path("driverAssignedEventId").asText());

        ProcessedEvent recorded = savedEvent();
        assertEquals(EVENT_ID, recorded.getEventId());
        assertEquals("DriverAssigned", recorded.getEventType());
        assertNotNull(recorded.getProcessedAt());
    }

    @Test
    @DisplayName("onDriverAssigned for a reservation that no longer matches queues DriverAssignmentRejected and records the event")
    void onDriverAssigned_reservationDoesNotMatch_queuesRejectedAndRecordsEvent() {
        assign(CORRELATION_ID, PASSENGER_ID, 0);

        OutboxEvent row = queuedResult();
        assertEquals("DriverAssignmentRejected", row.getEventType());
        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        assertEquals("DriverAssignmentRejected", envelope.path("eventType").asText());
        assertEquals(EVENT_ID.toString(), envelope.path("causationId").asText());
        // Same payload as a confirmation, so trip-service can match it to its pending command
        JsonNode payload = envelope.path("payload");
        assertEquals(DRIVER_ID.toString(), payload.path("driverId").asText());
        assertEquals(RESERVATION_VERSION, payload.path("reservationVersion").intValue());
        assertEquals(EVENT_ID.toString(), payload.path("driverAssignedEventId").asText());
        assertEquals("DriverAssigned", savedEvent().getEventType());
    }

    @Test
    @DisplayName("A DriverAssigned without passengerId gives a result without passengerId")
    void onDriverAssigned_withoutPassengerId_leavesPassengerIdOut() {
        assign(CORRELATION_ID, null, 1);

        JsonNode payload = jsonMapper.readTree(queuedResult().getPayload()).path("payload");
        assertFalse(payload.has("passengerId"));
        assertEquals(TRIP_ID.toString(), payload.path("tripId").asText());
    }

    @ParameterizedTest(name = "correlationId = \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"corr-123", "e2e-run-1"})
    @DisplayName("A missing or non-UUID correlationId is replaced by a new UUID, which trip-service requires")
    void onDriverAssigned_correlationIdNotUuid_usesNewUuid(String correlationId) {
        assign(correlationId, PASSENGER_ID, 1);

        String used = jsonMapper.readTree(queuedResult().getPayload()).path("correlationId").asText();
        assertDoesNotThrow(() -> UUID.fromString(used));
        assertNotEquals(correlationId, used);
    }

    @Test
    @DisplayName("Every result gets an eventId of its own")
    void onDriverAssigned_result_hasNewEventId() {
        assign(CORRELATION_ID, PASSENGER_ID, 1);

        OutboxEvent row = queuedResult();
        assertNotEquals(EVENT_ID, row.getId());
    }

    @Test
    @DisplayName("onTripCompleted releases only a BUSY driver of the trip and records the event")
    void onTripCompleted_newEvent_releasesBusyDriverAndRecordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenReturn(1);

        service.onTripCompleted(EVENT_ID, TRIP_ID);

        verify(driverRepository).releaseForTrip(eq(TRIP_ID), eq(List.of(DriverStatus.BUSY)), any());
        assertEquals("TripCompleted", savedEvent().getEventType());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("onTripCancelled releases a RESERVED or BUSY driver of the trip and records the event")
    void onTripCancelled_newEvent_releasesReservedOrBusyDriverAndRecordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenReturn(0);

        service.onTripCancelled(EVENT_ID, TRIP_ID);

        verify(driverRepository).releaseForTrip(eq(TRIP_ID), eq(List.of(DriverStatus.RESERVED, DriverStatus.BUSY)), any());
        assertEquals("TripCancelled", savedEvent().getEventType());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("An event that was already processed changes nothing and queues no second result")
    void onEvent_duplicateEventId_skipsWithoutTouchingDrivers() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        service.onDriverAssigned(EVENT_ID, CORRELATION_ID, TRIP_ID, DRIVER_ID, PASSENGER_ID, RESERVATION_VERSION);
        service.onTripCompleted(EVENT_ID, TRIP_ID);
        service.onTripCancelled(EVENT_ID, TRIP_ID);

        verifyNoInteractions(driverRepository, outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A failed driver update queues no result and is not recorded, so the retry runs it again")
    void onDriverAssigned_updateFails_queuesNothing() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.markBusy(eq(DRIVER_ID), eq(TRIP_ID), eq(RESERVATION_VERSION), any()))
                .thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> service.onDriverAssigned(
                EVENT_ID, CORRELATION_ID, TRIP_ID, DRIVER_ID, PASSENGER_ID, RESERVATION_VERSION));

        verifyNoInteractions(outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A failed update is not recorded as processed, so the retry runs it again")
    void onTripCompleted_updateFails_doesNotRecordEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> service.onTripCompleted(EVENT_ID, TRIP_ID));

        verify(processedEventRepository, never()).save(any());
    }
}

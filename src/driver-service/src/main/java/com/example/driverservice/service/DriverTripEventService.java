package com.example.driverservice.service;

import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.OutboxEvent;
import com.example.driverservice.entity.ProcessedEvent;
import com.example.driverservice.kafka.event.DriverAssignmentResultPayload;
import com.example.driverservice.kafka.event.EventEnvelope;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.OutboxEventRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Driver status changes driven by trip events (docs/events.md §4.3, §4.5, §4.8). Every method runs in one
 * transaction: skip an eventId that is already in processed_events, change the driver, record the eventId.
 * A failure rolls both back, so the event is handled again on the retry. An event that matches no driver is
 * recorded too: it was handled, there was just nothing to change.
 * DriverAssigned is answered with DriverAssignmentConfirmed or DriverAssignmentRejected (§4.10, §4.11), written to
 * the outbox in the same transaction; DriverEventOutboxRelay publishes it to driver-events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverTripEventService {

    public static final String DRIVER_ASSIGNED = "DriverAssigned";
    public static final String TRIP_COMPLETED = "TripCompleted";
    public static final String TRIP_CANCELLED = "TripCancelled";
    public static final String DRIVER_ASSIGNMENT_CONFIRMED = "DriverAssignmentConfirmed";
    public static final String DRIVER_ASSIGNMENT_REJECTED = "DriverAssignmentRejected";
    public static final String PRODUCER = "driver-service";
    public static final String AGGREGATE_TYPE = "Trip";

    private static final List<DriverStatus> ON_TRIP = List.of(DriverStatus.BUSY);
    private static final List<DriverStatus> HELD_BY_TRIP = List.of(DriverStatus.RESERVED, DriverStatus.BUSY);

    private final DriverRepository driverRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * RESERVED -> BUSY only for the exact reservation the command names: this driver, this trip, the version the
     * reservation produced and a lease that is still running. Either way exactly one result goes to the outbox:
     * DriverAssignmentConfirmed when the driver became BUSY, DriverAssignmentRejected otherwise. A rejection is a
     * normal outcome, not an error: the driver is left as it is and trip-service starts a bounded rematch.
     *
     * @param correlationId the correlationId of DriverAssigned; a new UUID is used when it is missing or not a UUID
     * @param passengerId   copied into the result for notification-service; may be null
     */
    @Transactional
    public void onDriverAssigned(UUID eventId, String correlationId, UUID tripId, UUID driverId, UUID passengerId,
                                 int reservationVersion) {
        if (alreadyProcessed(eventId, DRIVER_ASSIGNED, tripId)) {
            return;
        }
        Instant now = Instant.now();
        boolean confirmed = driverRepository.markBusy(driverId, tripId, reservationVersion, now) == 1;
        if (confirmed) {
            log.info("Driver assignment confirmed, RESERVED -> BUSY: driverId={}, tripId={}, reservationVersion={}, eventId={}",
                    driverId, tripId, reservationVersion, eventId);
        } else {
            // Another trip, another reservation version, the lease ran out, or the reservation was released
            log.warn("Driver assignment rejected, reservation does not match: driverId={}, tripId={}, reservationVersion={}, eventId={}",
                    driverId, tripId, reservationVersion, eventId);
        }
        enqueueResult(confirmed ? DRIVER_ASSIGNMENT_CONFIRMED : DRIVER_ASSIGNMENT_REJECTED, eventId, correlationId,
                new DriverAssignmentResultPayload(tripId, driverId, passengerId, reservationVersion, eventId), now);
        recordProcessed(eventId, DRIVER_ASSIGNED);
    }

    /** BUSY -> AVAILABLE for the driver of this trip, whatever the payment result will be. */
    @Transactional
    public void onTripCompleted(UUID eventId, UUID tripId) {
        if (alreadyProcessed(eventId, TRIP_COMPLETED, tripId)) {
            return;
        }
        release(eventId, TRIP_COMPLETED, tripId, ON_TRIP);
        recordProcessed(eventId, TRIP_COMPLETED);
    }

    /** RESERVED or BUSY -> AVAILABLE for the driver of this trip; a trip cancelled before matching has none. */
    @Transactional
    public void onTripCancelled(UUID eventId, UUID tripId) {
        if (alreadyProcessed(eventId, TRIP_CANCELLED, tripId)) {
            return;
        }
        release(eventId, TRIP_CANCELLED, tripId, HELD_BY_TRIP);
        recordProcessed(eventId, TRIP_CANCELLED);
    }

    private void release(UUID eventId, String eventType, UUID tripId, List<DriverStatus> statuses) {
        if (driverRepository.releaseForTrip(tripId, statuses, Instant.now()) > 0) {
            log.info("Driver released, -> AVAILABLE: eventType={}, tripId={}, eventId={}", eventType, tripId, eventId);
        } else {
            // No driver yet, already released by DELETE /reserve or the lease, or already on another trip
            log.info("{} skipped, no driver is held by the trip: tripId={}, eventId={}", eventType, tripId, eventId);
        }
    }

    private boolean alreadyProcessed(UUID eventId, String eventType, UUID tripId) {
        if (processedEventRepository.existsById(eventId)) {
            log.warn("Duplicate event detected, skipping: eventId={}, eventType={}, tripId={}", eventId, eventType, tripId);
            return true;
        }
        return false;
    }

    private void recordProcessed(UUID eventId, String eventType) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .processedAt(Instant.now())
                .build());
    }

    // The whole envelope is stored, so a resend after a Kafka failure keeps the same eventId
    private void enqueueResult(String eventType, UUID driverAssignedEventId, String correlationId,
                               DriverAssignmentResultPayload payload, Instant now) {
        UUID resultEventId = UUID.randomUUID();
        String tripId = payload.tripId().toString();
        EventEnvelope<DriverAssignmentResultPayload> envelope = EventEnvelope.<DriverAssignmentResultPayload>builder()
                .eventId(resultEventId.toString())
                .eventType(eventType)
                .aggregateId(tripId)
                .aggregateType(AGGREGATE_TYPE)
                .producer(PRODUCER)
                .correlationId(correlationIdOrNew(correlationId))
                .causationId(driverAssignedEventId.toString())
                .occurredAt(now)
                .payload(payload)
                .build();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(resultEventId)
                .aggregateId(tripId)
                .aggregateType(AGGREGATE_TYPE)
                .eventType(eventType)
                .payload(objectMapper.writeValueAsString(envelope))
                .createdAt(now)
                .build());
    }

    // trip-service rejects an envelope whose correlationId is not a UUID (docs/events.md §10)
    private static String correlationIdOrNew(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            try {
                UUID parsed = UUID.fromString(correlationId);
                if (parsed.toString().equalsIgnoreCase(correlationId)) {
                    return correlationId;
                }
            } catch (IllegalArgumentException ignored) {
                // falls through to a new correlationId
            }
        }
        return UUID.randomUUID().toString();
    }
}

package com.example.driverservice.service;

import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.ProcessedEvent;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Driver status changes driven by trip events (docs/events.md §4.3, §4.5, §4.8). Every method runs in one
 * transaction: skip an eventId that is already in processed_events, change the driver, record the eventId.
 * A failure rolls both back, so the event is handled again on the retry. An event that matches no driver is
 * recorded too: it was handled, there was just nothing to change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverTripEventService {

    public static final String DRIVER_ASSIGNED = "DriverAssigned";
    public static final String TRIP_COMPLETED = "TripCompleted";
    public static final String TRIP_CANCELLED = "TripCancelled";

    private static final List<DriverStatus> ON_TRIP = List.of(DriverStatus.BUSY);
    private static final List<DriverStatus> HELD_BY_TRIP = List.of(DriverStatus.RESERVED, DriverStatus.BUSY);

    private final DriverRepository driverRepository;
    private final ProcessedEventRepository processedEventRepository;

    /** RESERVED -> BUSY, only for the driver reserved for this trip. */
    @Transactional
    public void onDriverAssigned(UUID eventId, UUID tripId, UUID driverId) {
        if (alreadyProcessed(eventId, DRIVER_ASSIGNED, tripId)) {
            return;
        }
        if (driverRepository.markBusy(driverId, tripId, Instant.now()) == 1) {
            log.info("Driver assigned, RESERVED -> BUSY: driverId={}, tripId={}, eventId={}", driverId, tripId, eventId);
        } else {
            // The lease expired, the reservation was released, or the driver is already BUSY with this trip
            log.warn("DriverAssigned skipped, driver is not RESERVED for the trip: driverId={}, tripId={}, eventId={}",
                    driverId, tripId, eventId);
        }
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
}

package com.example.matchingservice.service;

import com.example.matchingservice.client.*;
import com.example.matchingservice.dto.*;
import com.example.matchingservice.entity.*;
import com.example.matchingservice.event.*;
import com.example.matchingservice.exception.*;
import com.example.matchingservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock
    MatchRequestRepository requests;

    @Mock
    DriverOfferRepository offers;

    @Mock
    DriverServiceClient drivers;

    @Mock
    TripServiceClient trips;

    @Mock
    ProcessedEventRepository processed;

    @Mock
    MatchingEventPublisher events;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    MatchingServiceImpl service;

    UUID trip = UUID.randomUUID(), d1 = UUID.randomUUID(), d2 = UUID.randomUUID();

    MatchRequest request;

    @BeforeEach
    void setup() {
        request = MatchRequest.builder()
            .requestId(trip)
            .tripId(trip)
            .passengerId(UUID.randomUUID())
            .pickupLat(21.0)
            .pickupLng(105.0)
            .correlationId(UUID.randomUUID().toString())
            .expiresAt(clock.instant().plusSeconds(60))
            .build();
        lenient().when(requests.findByIdForUpdate(trip)).thenReturn(Optional.of(request));
        lenient().when(requests.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(offers.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(trips.getTripStatus(trip)).thenReturn("SEARCHING");
    }

    AvailableDriverDto candidate(UUID id, double distance) {
        return AvailableDriverDto.builder()
            .id(id)
            .fullName("Driver")
            .status("AVAILABLE")
            .distanceKm(distance)
            .version(1)
            .location(AvailableDriverDto.DriverLocation.builder().latitude(21.0).longitude(105.0).build())
            .build();
    }

    ReserveDriverResponse reservation(UUID d, Instant until) {
        return ReserveDriverResponse.builder()
            .driverId(d)
            .reservedForTrip(trip)
            .status("RESERVED")
            .version(2)
            .reservedUntil(until)
            .build();
    }

    @Test
    void conflictFallsBackToNextClosestDriver() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(List.of(candidate(d2, 2), candidate(d1, 1)));
        when(drivers.reserveDriver(d1, trip, 1)).thenThrow(new DriverReservationConflictException(d1));
        var reserved = reservation(d2, clock.instant().plusSeconds(30));
        when(drivers.reserveDriver(d2, trip, 1)).thenReturn(reserved);
        assertEquals(d2, service.matchDriver(trip, null).getMatchedDriverId());
        var ordered = inOrder(drivers);
        ordered.verify(drivers).getAvailableDrivers(21.0, 105.0, 5.0);
        ordered.verify(drivers).reserveDriver(d1, trip, 1);
        ordered.verify(drivers).reserveDriver(d2, trip, 1);
    }

    @Test
    void emptyCandidatesProducePersistedNoDriverFound() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        assertEquals(MatchStatus.NO_DRIVER_FOUND, service.matchDriver(trip, null).getStatus());
        assertEquals(MatchStatus.NO_DRIVER_FOUND, request.getStatus());
        verifyNoInteractions(events);
    }

    @Test
    void outboxFailureCompensatesReservation() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(candidate(d1, 1)));
        var reserved = reservation(d1, clock.instant().plusSeconds(30));
        when(drivers.reserveDriver(d1, trip, 1)).thenReturn(reserved);
        doThrow(new IllegalStateException("outbox unavailable")).when(events).publish(any());
        assertThrows(IllegalStateException.class, () -> service.matchDriver(trip, null));
        verify(drivers).releaseDriverReservation(d1, trip);
    }

    @Test
    void expiredReservationIsReleasedAndNeverPublished() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(candidate(d1, 1)));
        var reserved = reservation(d1, clock.instant().minusSeconds(1));
        when(drivers.reserveDriver(d1, trip, 1)).thenReturn(reserved);
        assertEquals(MatchStatus.NO_DRIVER_FOUND, service.matchDriver(trip, null).getStatus());
        verify(drivers).releaseDriverReservation(d1, trip);
        verifyNoInteractions(events);
    }

    @Test
    void exhaustedAttemptsRequestCancellationFromTripOnly() {
        request.setStatus(MatchStatus.NO_DRIVER_FOUND);
        request.setAttemptCount(3);
        service.retryOrExpire(trip);
        verify(trips).cancelForMatchingTimeout(trip, request.getCorrelationId());
        assertEquals(MatchStatus.EXPIRED, request.getStatus());
        verifyNoInteractions(events, drivers);
    }

    @Test
    void cancellationWinsWhileDriverWasBeingReserved() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(candidate(d1, 1)));
        var reserved = reservation(d1, clock.instant().plusSeconds(30));
        when(drivers.reserveDriver(d1, trip, 1)).thenReturn(reserved);
        when(trips.getTripStatus(trip)).thenReturn("CANCELLED");
        assertEquals(MatchStatus.EXPIRED, service.matchDriver(trip, null).getStatus());
        verify(drivers).releaseDriverReservation(d1, trip);
        verifyNoInteractions(events);
    }

    @Test
    void malformedCandidateCannotDefaultMissingVersion() {
        var invalid = candidate(d1, 1);
        invalid.setVersion(null);
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(invalid));
        assertEquals(MatchStatus.NO_DRIVER_FOUND, service.matchDriver(trip, null).getStatus());
        verify(drivers, never()).reserveDriver(any(), any(), any());
    }

    @Test
    void lateRideRequestedExpiresWithoutReserve() {
        var event = rideRequested(clock.instant().minusSeconds(61));
        when(requests.findByIdForUpdate(trip)).thenReturn(Optional.empty());
        when(requests.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service.handleRideRequested(event);
        verifyNoInteractions(drivers);
    }

    @Test
    void timelyRideRequestedUsesOriginalDeadline() {
        var requestedAt = clock.instant().minusSeconds(12);
        var event = rideRequested(requestedAt);
        when(requests.findByIdForUpdate(trip)).thenReturn(Optional.empty());
        when(requests.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        service.handleRideRequested(event);
        var captor = ArgumentCaptor.forClass(MatchRequest.class);
        verify(requests).saveAndFlush(captor.capture());
        assertEquals(requestedAt.plusSeconds(60), captor.getValue().getExpiresAt());
    }

    @Test
    void driverReservedCarriesActiveVersionAndEventId() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(candidate(d1, 1)));
        var reserved = reservation(d1, clock.instant().plusSeconds(30));
        when(drivers.reserveDriver(d1, trip, 1)).thenReturn(reserved);
        service.matchDriver(trip, null);
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        var event = captor.getValue();
        assertEquals(2, event.getPayload() instanceof Map<?, ?> p ? p.get("reservationVersion") : null);
        assertEquals(event.getEventId(), org.springframework.test.util.ReflectionTestUtils.getField(request, "activeReservationEventId"));
        assertEquals(2, org.springframework.test.util.ReflectionTestUtils.getField(request, "activeReservationVersion"));
    }

    private EventEnvelope<Map<String, Object>> rideRequested(Instant requestedAt) {
        return EventEnvelope.<Map<String, Object>>builder()
            .eventId(UUID.randomUUID()).eventType("RideRequested")
            .aggregateId(trip.toString()).aggregateType("Trip")
            .producer("trip-service").correlationId(request.getCorrelationId()).schemaVersion("1.0")
            .occurredAt(requestedAt)
            .payload(Map.of("tripId", trip.toString(), "passengerId", request.getPassengerId().toString(),
                "pickup", Map.of("latitude", 21.0, "longitude", 105.0), "requestedAt", requestedAt.toString()))
            .build();
    }

}

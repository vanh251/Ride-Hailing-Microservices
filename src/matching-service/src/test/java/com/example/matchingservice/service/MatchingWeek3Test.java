package com.example.matchingservice.service;

import com.example.matchingservice.client.*;
import com.example.matchingservice.dto.*;
import com.example.matchingservice.entity.*;
import com.example.matchingservice.event.*;
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
class MatchingWeek3Test {

    @Mock
    MatchRequestRepository requests;

    @Mock
    DriverOfferRepository offers;

    @Mock
    DriverServiceClient drivers;

    @Mock
    TripServiceClient trips;

    @Mock
    MatchingEventPublisher publisher;

    @Mock
    ProcessedEventRepository processed;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    MatchingServiceImpl service;

    UUID trip = UUID.randomUUID(), passenger = UUID.randomUUID(), source = UUID.randomUUID();

    @BeforeEach
    void persistence() {
        lenient().when(requests.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(requests.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(offers.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    EventEnvelope<Map<String, Object>> event() {
        return EventEnvelope.<Map<String, Object>>builder()
            .eventId(source)
            .eventType("RideRequested")
            .aggregateId(trip.toString())
            .aggregateType("Trip")
            .producer("trip-service")
            .correlationId(UUID.randomUUID().toString())
            .occurredAt(clock.instant())
            .payload(Map.of("tripId", trip.toString(), "passengerId", passenger.toString(), "pickup",
                    Map.of("latitude", 10.77, "longitude", 106.70), "destination",
                    Map.of("latitude", 10.78, "longitude", 106.71), "estimatedFare", 35000, "requestedAt",
                    clock.instant().toString()))
            .build();
    }

    AvailableDriverDto driver(UUID id, double distance) {
        return AvailableDriverDto.builder()
            .id(id)
            .fullName("Driver")
            .status("AVAILABLE")
            .version(3)
            .distanceKm(distance)
            .location(AvailableDriverDto.DriverLocation.builder().latitude(10.77).longitude(106.7).build())
            .build();
    }

    @Test
    void eventUsesRealPassengerAndPickup_andPublishesCausation() {
        UUID d = UUID.randomUUID();
        when(drivers.getAvailableDrivers(10.77, 106.70, 5.0)).thenReturn(List.of(driver(d, 0.5)));
        var reservation = ReserveDriverResponse.builder()
            .driverId(d)
            .reservedForTrip(trip)
            .status("RESERVED")
            .reservedUntil(clock.instant().plusSeconds(30))
            .version(4)
            .build();
        when(drivers.reserveDriver(d, trip, 3)).thenReturn(reservation);
        when(trips.getTripStatus(trip)).thenReturn("SEARCHING");
        service.handleRideRequested(event());
        var request = ArgumentCaptor.forClass(MatchRequest.class);
        verify(requests).saveAndFlush(request.capture());
        assertEquals(passenger, request.getValue().getPassengerId());
        assertEquals(10.77, request.getValue().getPickupLat());
        var out = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher).publish(out.capture());
        assertEquals(source.toString(), out.getValue().getCausationId());
        assertEquals(passenger, ((Map<?, ?>) out.getValue().getPayload()).get("passengerId"));
    }

    @Test
    void noDriversIsPersistedBusinessOutcome() {
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        service.handleRideRequested(event());
        var saved = ArgumentCaptor.forClass(MatchRequest.class);
        verify(requests, atLeastOnce()).save(saved.capture());
        assertEquals(MatchStatus.NO_DRIVER_FOUND, saved.getValue().getStatus());
        verifyNoInteractions(publisher);
        verify(processed).save(any());
    }

    @Test
    void duplicateDoesNotReserveAgain() {
        when(processed.existsById(any())).thenReturn(true);
        service.handleRideRequested(event());
        verifyNoInteractions(drivers, publisher);
    }

    @Test
    void unknownRestRequestDoesNotInventPassenger() {
        assertThrows(com.example.matchingservice.exception.MatchRequestNotFoundException.class,
                () -> service.matchDriver(trip, UUID.randomUUID().toString()));
        verifyNoInteractions(drivers, publisher);
    }

    @Test
    void rematchCannotReplaceInFlightCommittedAssignment() {
        var r = MatchRequest.builder()
            .requestId(trip)
            .tripId(trip)
            .passengerId(passenger)
            .status(MatchStatus.MATCHED)
            .matchedDriverId(UUID.randomUUID())
            .build();
        when(requests.findByIdForUpdate(trip)).thenReturn(Optional.of(r));
        assertThrows(com.example.matchingservice.exception.MatchingFailedException.class,
                () -> service.rematchDriver(trip, UUID.randomUUID().toString()));
        verifyNoInteractions(drivers, publisher);
    }

}

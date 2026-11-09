package com.example.tripservice.service;

import com.example.tripservice.client.PricingServiceClient;
import com.example.tripservice.dto.*;
import com.example.tripservice.entity.*;
import com.example.tripservice.event.*;
import com.example.tripservice.mapper.TripMapper;
import com.example.tripservice.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripWeek3Test {

    @Mock
    TripRepository trips;

    @Mock
    RideRequestRepository rides;

    @Mock
    TripStatusHistoryRepository history;

    @Mock
    SagaLogRepository saga;

    @Mock
    PricingServiceClient pricing;

    @Mock
    com.example.tripservice.client.DriverServiceClient drivers;

    @Spy
    java.time.Clock clock = java.time.Clock.systemUTC();

    @Mock
    TripEventPublisher events;

    @Spy
    TripMapper mapper = new TripMapper();

    @InjectMocks
    TripServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void persistenceBoundary() {
        lenient().when(trips.save(any())).thenAnswer(i -> {
            Trip t = i.getArgument(0);
            if (t.getTripId() == null)
                t.setTripId(UUID.randomUUID());
            t.prePersist();
            return t;
        });
        lenient().when(rides.save(any())).thenAnswer(i -> {
            RideRequest r = i.getArgument(0);
            if (r.getRequestId() == null)
                r.setRequestId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void missingIdentityCannotCreateRandomPassenger() {
        var request = CreateTripRequest.builder()
            .passengerId(UUID.randomUUID())
            .pickup(LocationDto.builder().latitude(21.0).longitude(105.0).build())
            .destination(LocationDto.builder().latitude(21.1).longitude(105.1).build())
            .build();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.createTrip(request, UUID.randomUUID().toString(), null));
        verifyNoInteractions(trips, rides, events);
    }

    @Test
    void acceptCannotAssignSearchingTrip() {
        var trip = trip(TripStatus.SEARCHING);
        when(trips.findByIdForUpdate(trip.getTripId())).thenReturn(Optional.of(trip));
        assertThrows(com.example.tripservice.exception.InvalidTripStateException.class,
                () -> service.acceptTrip(trip.getTripId(), UUID.randomUUID()));
        assertNull(trip.getDriverId());
    }

    @Test
    void acceptCannotOverwriteMatchedDriver() {
        var trip = trip(TripStatus.ASSIGNED);
        var driver = UUID.randomUUID();
        trip.setDriverId(driver);
        when(trips.findByIdForUpdate(trip.getTripId())).thenReturn(Optional.of(trip));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.acceptTrip(trip.getTripId(), UUID.randomUUID()));
        assertEquals(driver, trip.getDriverId());
    }

    static Trip trip(TripStatus status) {
        return Trip.builder()
            .tripId(UUID.randomUUID())
            .passengerId(UUID.randomUUID())
            .status(status)
            .pickupLat(21.0)
            .pickupLng(105.0)
            .dropoffLat(21.1)
            .dropoffLng(105.1)
            .correlationId(UUID.randomUUID().toString())
            .estimatedFare(new BigDecimal("35000"))
            .createdAt(Instant.now())
            .build();
    }

}

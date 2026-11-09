package com.example.matchingservice.concurrency;

import com.example.matchingservice.client.*;
import com.example.matchingservice.dto.*;
import com.example.matchingservice.entity.*;
import com.example.matchingservice.event.*;
import com.example.matchingservice.repository.*;
import com.example.matchingservice.service.MatchingServiceImpl;
import com.example.matchingservice.exception.DriverReservationConflictException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchingConcurrencyTest {

    @Test
    void sameDriverHasOnlyOneWinner() throws Exception {
        race(false);
    }

    @Test
    void conflictLoserRematchesToSecondDriver() throws Exception {
        race(true);
    }

    void race(boolean fallback) throws Exception {
        var requests = mock(MatchRequestRepository.class);
        var offers = mock(DriverOfferRepository.class);
        var drivers = mock(DriverServiceClient.class);
        var trips = mock(TripServiceClient.class);
        var events = mock(MatchingEventPublisher.class);
        var processed = mock(ProcessedEventRepository.class);
        Clock clock = Clock.systemUTC();
        var service = new MatchingServiceImpl(requests, offers, drivers, events, processed, trips, clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), d = UUID.randomUUID(), spare = UUID.randomUUID();
        for (var id : List.of(a, b)) {
            var r = MatchRequest.builder()
                .requestId(id)
                .tripId(id)
                .passengerId(UUID.randomUUID())
                .pickupLat(21.0)
                .pickupLng(105.0)
                .correlationId(UUID.randomUUID().toString())
                .expiresAt(clock.instant().plusSeconds(60))
                .build();
            when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(r));
        }
        when(requests.save(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(trips.getTripStatus(any())).thenReturn("SEARCHING");
        var first = candidate(d, 1);
        var second = candidate(spare, 2);
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(fallback ? List.of(first, second) : List.of(first));
        var held = new AtomicBoolean();
        when(drivers.reserveDriver(any(), any(), eq(1))).thenAnswer(i -> {
            UUID selected = i.getArgument(0), trip = i.getArgument(1);
            if (selected.equals(d) && !held.compareAndSet(false, true))
                throw new DriverReservationConflictException(d);
            return ReserveDriverResponse.builder()
                .driverId(selected)
                .reservedForTrip(trip)
                .status("RESERVED")
                .version(2)
                .reservedUntil(clock.instant().plusSeconds(30))
                .build();
        });
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                start.await();
                return service.matchDriver(a, null);
            });
            var two = executor.submit(() -> {
                start.await();
                return service.matchDriver(b, null);
            });
            start.countDown();
            var results = List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> d.equals(r.getMatchedDriverId())).count());
            if (fallback) {
                assertEquals(2, results.stream().filter(r -> r.getStatus() == MatchStatus.MATCHED).count());
                assertEquals(1, results.stream().filter(r -> spare.equals(r.getMatchedDriverId())).count());
            }
            else
                assertEquals(1, results.stream().filter(r -> r.getStatus() == MatchStatus.NO_DRIVER_FOUND).count());
            verify(events, times(fallback ? 2 : 1)).publish(any());
        }
        finally {
            executor.shutdownNow();
        }
    }

    AvailableDriverDto candidate(UUID id, double distance) {
        return AvailableDriverDto.builder()
            .id(id)
            .fullName("Driver")
            .status("AVAILABLE")
            .version(1)
            .distanceKm(distance)
            .location(AvailableDriverDto.DriverLocation.builder().latitude(21.0).longitude(105.0).build())
            .build();
    }

}

package com.example.tripservice.service;

import com.example.tripservice.client.*;
import com.example.tripservice.dto.*;
import com.example.tripservice.entity.*;
import com.example.tripservice.event.*;
import com.example.tripservice.exception.*;
import com.example.tripservice.mapper.TripMapper;
import com.example.tripservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @Mock
    TripRepository trips;

    @Mock
    RideRequestRepository rides;

    @Mock
    TripStatusHistoryRepository histories;

    @Mock
    SagaLogRepository sagas;

    @Mock
    PricingServiceClient pricing;

    @Mock
    DriverServiceClient drivers;

    @Mock
    TripEventPublisher events;

    @Spy
    TripMapper mapper = new TripMapper();

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    TripServiceImpl service;

    UUID id = UUID.randomUUID(), passenger = UUID.randomUUID(), driver = UUID.randomUUID();

    @BeforeEach
    void persistence() {
        lenient().when(trips.save(any())).thenAnswer(i -> {
            Trip t = i.getArgument(0);
            if (t.getTripId() == null)
                t.setTripId(id);
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

    Trip trip(TripStatus status) {
        var t = Trip.builder()
            .tripId(id)
            .passengerId(passenger)
            .driverId(driver)
            .status(status)
            .distanceKm(2.8)
            .currency("VND")
            .estimatedFare(new BigDecimal("33500"))
            .correlationId(UUID.randomUUID().toString())
            .startedAt(clock.instant().minusSeconds(1200))
            .createdAt(clock.instant().minusSeconds(1300))
            .pickupLat(21.0)
            .pickupLng(105.0)
            .dropoffLat(21.1)
            .dropoffLng(105.1)
            .build();
        when(trips.findByIdForUpdate(id)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void quoteControlsFareAndIdentity_notClientInput() {
        var location = LocationDto.builder().latitude(21.0).longitude(105.0).build();
        var request = CreateTripRequest.builder()
            .pickup(location)
            .destination(location)
            .passengerId(UUID.randomUUID())
            .estimatedFare(BigDecimal.ONE)
            .build();
        when(pricing.getQuote(location, location))
            .thenReturn(new PricingQuoteResponse(2.5, 12, new BigDecimal("33500"), "VND"));
        var result = service.createTrip(request, UUID.randomUUID().toString(), passenger);
        assertEquals(passenger, result.getPassengerId());
        assertEquals(new BigDecimal("33500"), result.getEstimatedFare());
        assertEquals(TripStatus.SEARCHING, result.getStatus());
        verify(events).publish(argThat(e -> "RideRequested".equals(e.getEventType()) && e.getCausationId() == null));
    }

    @Test
    void acceptPreservesAssignment_withoutNewHistory() {
        trip(TripStatus.ASSIGNED);
        assertEquals(driver, service.acceptTrip(id, driver).getDriverId());
        verifyNoInteractions(histories, events);
    }

    @Test
    void startPublishesPassengerAndNeverEmptyDriver() {
        trip(TripStatus.ASSIGNED);
        assertEquals(TripStatus.IN_PROGRESS, service.startTrip(id, driver).getStatus());
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        var payload = (Map<?, ?>) captor.getValue().getPayload();
        assertEquals(passenger, payload.get("passengerId"));
        assertEquals(driver, payload.get("driverId"));
    }

    @Test
    void completeHasEightFieldsAndChargesPricingAmount() {
        var t = trip(TripStatus.IN_PROGRESS);
        when(pricing.getFinalFare(id, 2.8, 20))
            .thenReturn(new PricingFinalFareResponse(id, new BigDecimal("47500"), "VND"));
        assertEquals(TripStatus.COMPLETED, service.completeTrip(id, driver).getStatus());
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        var p = (Map<?, ?>) captor.getValue().getPayload();
        assertEquals(Set.of("tripId", "driverId", "passengerId", "actualDistanceKm", "actualDurationMin", "finalFare",
                "currency", "completedAt"), p.keySet());
        assertEquals(new BigDecimal("47500"), p.get("finalFare"));
        assertEquals(20, p.get("actualDurationMin"));
        assertEquals(passenger, p.get("passengerId"));
        assertEquals("VND", p.get("currency"));
        assertThrows(InvalidTripStateException.class, () -> service.completeTrip(id, driver));
    }

    @Test
    void pricingFailureDoesNotCompleteOrPublish() {
        var t = trip(TripStatus.IN_PROGRESS);
        when(pricing.getFinalFare(id, 2.8, 20)).thenThrow(new RestClientException("unavailable"));
        assertThrows(RestClientException.class, () -> service.completeTrip(id, driver));
        assertEquals(TripStatus.IN_PROGRESS, t.getStatus());
        assertNull(t.getFinalFare());
        verifyNoInteractions(events, histories, sagas);
    }

    @Test
    void paymentSuccessAndFailureAreTerminal_andWrongPaymentRejected() {
        var t = trip(TripStatus.COMPLETED);
        t.setFinalFare(new BigDecimal("47500"));
        assertThrows(ValidationException.class,
                () -> service.applyPaymentResult(id, UUID.randomUUID(), passenger, BigDecimal.ONE, "VND", true, null));
        assertEquals(TripStatus.COMPLETED, t.getStatus());
        service.applyPaymentResult(id, UUID.randomUUID(), passenger, new BigDecimal("47500.00"), "VND", true, null);
        assertEquals(TripStatus.PAID, t.getStatus());
        service.applyPaymentResult(id, UUID.randomUUID(), passenger, new BigDecimal("47500"), "VND", false, "DECLINED");
        assertEquals(TripStatus.PAID, t.getStatus());
        verify(histories, times(1)).save(any());
    }

    @Test
    void paymentFailureDoesNotCancelOrTouchDriver() {
        var t = trip(TripStatus.COMPLETED);
        t.setFinalFare(new BigDecimal("47500"));
        service.applyPaymentResult(id, UUID.randomUUID(), passenger, new BigDecimal("47500"), "VND", false, "DECLINED");
        assertEquals(TripStatus.PAYMENT_FAILED, t.getStatus());
        verifyNoInteractions(drivers, events);
    }

    @Test
    void cancellationReleasesOnlyItsTrip_andReplayDoesNotPublishAgain() {
        var t = trip(TripStatus.ASSIGNED);
        assertEquals(TripStatus.CANCELLED, service.cancelTrip(id, null, passenger).getStatus());
        service.cancelTrip(id, null, passenger);
        verify(drivers, times(1)).releaseDriverReservation(driver, id);
        verify(events, times(1)).publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
    }

    @Test
    void timeoutCannotCancelAssignedTrip_andLateAssignmentCannotResurrectCancellation() {
        var t = trip(TripStatus.ASSIGNED);
        assertThrows(InvalidTripStateException.class, () -> service.cancelForMatchingTimeout(id));
        t.setStatus(TripStatus.CANCELLED);
        service.assignDriver(id, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(TripStatus.CANCELLED, t.getStatus());
        verifyNoInteractions(events);
    }

}

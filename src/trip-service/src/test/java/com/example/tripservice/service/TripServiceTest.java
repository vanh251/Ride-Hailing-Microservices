package com.example.tripservice.service;

import com.example.tripservice.client.*;
import com.example.tripservice.dto.*;
import com.example.tripservice.entity.*;
import com.example.tripservice.event.*;
import com.example.tripservice.exception.*;
import com.example.tripservice.mapper.TripMapper;
import com.example.tripservice.repository.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
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
    void acceptPersistsConfirmation_andRepeatedAcceptPreservesIt() {
        var t = trip(TripStatus.ASSIGNED);
        var response = service.acceptTrip(id, driver);
        verify(trips).save(t);
        assertTrue(JsonMapper.builder().build().writeValueAsString(response).contains("\"acceptedAt\":\"2026-11-09T12:00:00Z\""));
        service.acceptTrip(id, driver);
        verify(trips, times(1)).save(t);
        verifyNoInteractions(histories, events);
    }

    @Test
    void cannotStartBeforeDriverAccepts() {
        var t = trip(TripStatus.ASSIGNED);
        assertThrows(InvalidTripStateException.class, () -> service.startTrip(id, driver));
        assertEquals(TripStatus.ASSIGNED, t.getStatus());
        verifyNoInteractions(events, histories);
    }

    @Test
    void startPublishesPassengerAndNeverEmptyDriver() {
        trip(TripStatus.ASSIGNED);
        service.acceptTrip(id, driver);
        assertEquals(TripStatus.IN_PROGRESS, service.startTrip(id, driver).getStatus());
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        var payload = (Map<?, ?>) captor.getValue().getPayload();
        assertEquals(passenger, payload.get("passengerId"));
        assertEquals(driver, payload.get("driverId"));
    }

    @Test
    void completeCarriesProviderAndChargesPricingAmount() {
        var t = trip(TripStatus.IN_PROGRESS);
        when(pricing.getFinalFare(id, 2.8, 20))
            .thenReturn(new PricingFinalFareResponse(id, new BigDecimal("47500"), "VND"));
        assertEquals(TripStatus.COMPLETED, service.completeTrip(id, driver).getStatus());
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        var p = (Map<?, ?>) captor.getValue().getPayload();
        assertEquals(Set.of("tripId", "driverId", "passengerId", "actualDistanceKm", "actualDurationMin", "finalFare",
                "currency", "completedAt", "paymentProvider"), p.keySet());
        assertEquals(new BigDecimal("47500"), p.get("finalFare"));
        assertEquals(20, p.get("actualDurationMin"));
        assertEquals(passenger, p.get("passengerId"));
        assertEquals("VND", p.get("currency"));
        assertEquals("MOCK", p.get("paymentProvider"));
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
    void paymentFailurePersistsDebtDetailsWithoutReleasingDriver() {
        var t = trip(TripStatus.COMPLETED);
        t.setFinalFare(new BigDecimal("47500"));
        UUID paymentId = UUID.randomUUID();
        service.applyPaymentResult(id, paymentId, passenger, new BigDecimal("47500"), "VND", false, "DECLINED");
        var captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagas).save(captor.capture());
        var payload = captor.getValue().getPayload();
        assertTrue(payload.contains(paymentId.toString()));
        assertTrue(payload.contains("47500"));
        assertTrue(payload.contains("VND"));
        assertTrue(payload.contains("DECLINED"));
        verifyNoInteractions(drivers);
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
        service.beginAssignment(id, UUID.randomUUID(), 2, clock.instant().plusSeconds(10), UUID.randomUUID());
        assertEquals(TripStatus.CANCELLED, t.getStatus());
        verifyNoInteractions(events);
    }

    @Test
    void staleReservationRequestsRecoveryWithoutAssignment() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(40));
        UUID source = UUID.randomUUID();
        service.beginAssignment(id, driver, 2, clock.instant().minusSeconds(1), source);
        assertEquals(TripStatus.SEARCHING, t.getStatus());
        verify(events).publish(argThat(e -> "AssignmentRecoveryRequested".equals(e.getEventType())));
        verify(events, never()).publish(argThat(e -> "DriverAssigned".equals(e.getEventType())));
    }

    @Test
    void liveLeaseAfterSearchDeadlineCancelsInsteadOfAssigning() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(61));
        service.beginAssignment(id, driver, 2, clock.instant().plusSeconds(20), UUID.randomUUID());
        assertEquals(TripStatus.CANCELLED, t.getStatus());
        verify(events, never()).publish(argThat(e -> "DriverAssigned".equals(e.getEventType())));
    }

    @Test
    void liveReservationEntersAssigningAndPublishesVersionedCommand() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(10));
        UUID source = UUID.randomUUID();
        service.beginAssignment(id, driver, 2, clock.instant().plusSeconds(20), source);
        assertEquals(TripStatus.ASSIGNING, t.getStatus());
        assertNull(t.getDriverId());
        var captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(captor.capture());
        assertEquals("DriverAssigned", captor.getValue().getEventType());
        assertEquals(2, ((Map<?, ?>) captor.getValue().getPayload()).get("reservationVersion"));
    }

    @Test
    void cancelWhileAssigningPublishesTripCancelledAndSagaCompensation() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(10));
        service.beginAssignment(id, driver, 2, clock.instant().plusSeconds(20), UUID.randomUUID());
        assertEquals(TripStatus.CANCELLED, service.cancelTrip(id, null, passenger).getStatus());
        verify(events).publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
        verify(sagas).save(argThat(s -> "SAGA_COMPENSATED".equals(s.getStatus())));
    }

    @Test
    void startAndAcceptRejectAssigning() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(10));
        service.beginAssignment(id, driver, 2, clock.instant().plusSeconds(20), UUID.randomUUID());
        assertThrows(InvalidTripStateException.class, () -> service.acceptTrip(id, driver));
        assertThrows(InvalidTripStateException.class, () -> service.startTrip(id, driver));
    }

    private Trip pendingTrip() {
        var t = trip(TripStatus.SEARCHING);
        t.setDriverId(null);
        t.setCreatedAt(clock.instant().minusSeconds(10));
        service.beginAssignment(id, driver, 2, clock.instant().plusSeconds(20), UUID.randomUUID());
        return t;
    }

    @Test
    void matchingConfirmationCommitsAssigned() {
        var t = pendingTrip();
        service.applyAssignmentOutcome(id, driver, 2, t.getPendingAssignmentEventId(), true);
        assertEquals(TripStatus.ASSIGNED, t.getStatus());
        assertEquals(driver, t.getDriverId());
        assertNull(t.getPendingDriverId());
        verify(sagas).save(argThat(s -> "DRIVER_RESERVED".equals(s.getStatus())));
    }

    @Test
    void rejectionEmitsOneRecoveryRequest() {
        var t = pendingTrip();
        service.applyAssignmentOutcome(id, driver, 2, t.getPendingAssignmentEventId(), false);
        assertEquals(TripStatus.SEARCHING, t.getStatus());
        assertNull(t.getPendingDriverId());
        verify(events).publish(argThat(e -> "AssignmentRecoveryRequested".equals(e.getEventType())));
    }

    @Test
    void duplicateOutcomeIsNoOp() {
        var t = pendingTrip();
        UUID command = t.getPendingAssignmentEventId();
        service.applyAssignmentOutcome(id, driver, 2, command, true);
        service.applyAssignmentOutcome(id, driver, 2, command, true);
        verify(sagas, times(1)).save(argThat(s -> "DRIVER_RESERVED".equals(s.getStatus())));
    }

    @Test
    void lateConfirmationAfterDeadlineCancels() {
        var t = pendingTrip();
        t.setCreatedAt(clock.instant().minusSeconds(61));
        service.applyAssignmentOutcome(id, driver, 2, t.getPendingAssignmentEventId(), true);
        assertEquals(TripStatus.CANCELLED, t.getStatus());
        verify(events).publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
    }

    @Test
    void timeoutReleasesThroughTripCancelled() {
        var t = pendingTrip();
        t.setCreatedAt(clock.instant().minusSeconds(61));
        service.expirePendingAssignment(id);
        assertEquals(TripStatus.CANCELLED, t.getStatus());
        verify(events).publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
    }

    private final JsonMapper json = new JsonMapper();

    @Test
    void omittedProviderPersistsAndReturnsMockWithCurrency() {
        createAndCheckProvider("", "MOCK");
    }

    @Test
    void explicitPayosPersistsAndReturnsPayosWithCurrency() {
        createAndCheckProvider(",\"paymentProvider\":\"PAYOS\"", "PAYOS");
    }

    private void createAndCheckProvider(String providerJson, String expected) {
        var request = json.readValue("""
            {"pickup":{"latitude":21.0,"longitude":105.0},
             "destination":{"latitude":21.1,"longitude":105.1}%s}
            """.formatted(providerJson), CreateTripRequest.class);
        when(pricing.getQuote(any(), any()))
            .thenReturn(new PricingQuoteResponse(2.5, 12, new BigDecimal("33500"), "VND"));
        var response = service.createTrip(request, UUID.randomUUID().toString(), passenger);
        var saved = ArgumentCaptor.forClass(Trip.class);
        verify(trips).save(saved.capture());
        assertEquals(expected, saved.getValue().getPaymentProvider());
        assertEquals(expected, response.getPaymentProvider());
        assertEquals("VND", response.getCurrency());
    }

    @Test
    void completedEventCarriesSelectedProvider() {
        var trip = trip(TripStatus.IN_PROGRESS);
        trip.setPaymentProvider("PAYOS");
        when(pricing.getFinalFare(id, 2.8, 20))
            .thenReturn(new PricingFinalFareResponse(id, new BigDecimal("47500"), "VND"));
        service.completeTrip(id, driver);
        var event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(events).publish(event.capture());
        assertEquals("PAYOS", ((Map<?, ?>) event.getValue().getPayload()).get("paymentProvider"));
    }
}

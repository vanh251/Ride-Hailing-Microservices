package com.example.paymentservice;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.dto.response.PaymentResponse;
import com.example.paymentservice.entity.LedgerEntry;
import com.example.paymentservice.entity.LedgerEntryType;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.exception.BusinessException;
import com.example.paymentservice.exception.ConflictException;
import com.example.paymentservice.exception.PaymentDeclinedException;
import com.example.paymentservice.exception.ResourceNotFoundException;
import com.example.paymentservice.kafka.producer.PaymentEventProducer;
import com.example.paymentservice.repository.LedgerEntryRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.example.paymentservice.repository.ProcessedEventRepository;
import com.example.paymentservice.service.PaymentGatewaySimulator;
import com.example.paymentservice.service.PaymentService;
import com.example.paymentservice.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID PASSENGER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final BigDecimal AMOUNT = new BigDecimal("36500");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    @Mock
    private PaymentGatewaySimulator paymentGatewaySimulator;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(paymentRepository, processedEventRepository,
                ledgerEntryRepository, paymentEventProducer, paymentGatewaySimulator);
    }

    private PaymentChargeRequest chargeRequest(String idempotencyKey) {
        return PaymentChargeRequest.builder()
                .passengerId(PASSENGER_ID)
                .amount(AMOUNT)
                .currency("VND")
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private Payment existingPayment(PaymentStatus status, BigDecimal amount) {
        return Payment.builder()
                .paymentId(UUID.randomUUID())
                .tripId(TRIP_ID)
                .passengerId(PASSENGER_ID)
                .amount(amount)
                .currency("VND")
                .status(status)
                .failureReason(status == PaymentStatus.FAILED ? PaymentGatewaySimulator.SIMULATED_FAILURE : null)
                .idempotencyKey("idem-001")
                .correlationId("corr-001")
                .createdAt(Instant.now())
                .capturedAt(status == PaymentStatus.SUCCESS ? Instant.now() : null)
                .build();
    }

    @Test
    @DisplayName("charge with valid request captures payment, writes a CAPTURE ledger entry and a PaymentSucceeded outbox event")
    void charge_validRequest_createsSuccessfulPayment() {
        when(paymentRepository.findByIdempotencyKey("idem-001")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.charge(TRIP_ID, chargeRequest(null), "idem-001", "corr-001");

        assertEquals(TRIP_ID, response.getTripId());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertEquals(0, AMOUNT.compareTo(response.getAmount()));
        assertNotNull(response.getPaymentId());

        ArgumentCaptor<LedgerEntry> ledger = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledger.capture());
        assertEquals(LedgerEntryType.CAPTURE, ledger.getValue().getEntryType());
        assertEquals(0, AMOUNT.compareTo(ledger.getValue().getAmount()));
        verify(paymentEventProducer).publishPaymentSucceeded(any(Payment.class), isNull());
    }

    @Test
    @DisplayName("charge without header or body idempotency key is rejected with 400")
    void charge_missingIdempotencyKey_throwsBadRequest() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                paymentService.charge(TRIP_ID, chargeRequest(null), null, "corr-001"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("charge falls back to the body idempotency key when the header is absent")
    void charge_bodyIdempotencyKey_usesBodyKey() {
        when(paymentRepository.findByIdempotencyKey("body-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentService.charge(TRIP_ID, chargeRequest("body-key"), null, "corr-001");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertEquals("body-key", saved.getValue().getIdempotencyKey());
    }

    @Test
    @DisplayName("charge with duplicate idempotency key returns existing payment without re-charging")
    void charge_duplicateIdempotencyKey_returnsExistingPayment() {
        Payment existing = existingPayment(PaymentStatus.SUCCESS, AMOUNT);
        when(paymentRepository.findByIdempotencyKey("idem-001")).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.charge(TRIP_ID, chargeRequest(null), "idem-001", "corr-001");

        assertEquals(existing.getPaymentId(), response.getPaymentId());
        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentEventProducer, ledgerEntryRepository);
    }

    @Test
    @DisplayName("charge replaying the key of a declined payment returns 402 again without re-charging")
    void charge_duplicateKeyOfFailedPayment_throwsPaymentDeclined() {
        when(paymentRepository.findByIdempotencyKey("idem-001"))
                .thenReturn(Optional.of(existingPayment(PaymentStatus.FAILED, AMOUNT)));

        assertThrows(PaymentDeclinedException.class, () ->
                paymentService.charge(TRIP_ID, chargeRequest(null), "idem-001", "corr-001"));

        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    @DisplayName("charge with duplicate key but different amount throws ConflictException")
    void charge_duplicateKeyDifferentParams_throwsConflictException() {
        when(paymentRepository.findByIdempotencyKey("idem-001"))
                .thenReturn(Optional.of(existingPayment(PaymentStatus.SUCCESS, new BigDecimal("50000"))));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                paymentService.charge(TRIP_ID, chargeRequest(null), "idem-001", "corr-001"));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    }

    @Test
    @DisplayName("charge with a new key for a trip that is already paid throws ConflictException")
    void charge_tripAlreadyPaid_throwsConflictException() {
        when(paymentRepository.findByIdempotencyKey("idem-002")).thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(TRIP_ID, PaymentStatus.SUCCESS)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () ->
                paymentService.charge(TRIP_ID, chargeRequest(null), "idem-002", "corr-001"));

        assertEquals("PAYMENT_ALREADY_CAPTURED", ex.getCode());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("charge declined by the gateway saves a FAILED payment, a PaymentFailed event and throws 402")
    void charge_declined_savesFailedPaymentAndThrowsPaymentDeclined() {
        when(paymentRepository.findByIdempotencyKey("idem-001")).thenReturn(Optional.empty());
        when(paymentGatewaySimulator.isDeclined()).thenReturn(true);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentDeclinedException ex = assertThrows(PaymentDeclinedException.class, () ->
                paymentService.charge(TRIP_ID, chargeRequest(null), "idem-001", "corr-001"));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertEquals(PaymentStatus.FAILED, saved.getValue().getStatus());
        verify(paymentEventProducer).publishPaymentFailed(any(Payment.class), anyInt(), isNull());
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    @DisplayName("refund of a successful payment sets REFUNDED and writes a negative REFUND ledger entry")
    void refund_successfulPayment_updatesStatusToRefunded() {
        Payment payment = existingPayment(PaymentStatus.SUCCESS, AMOUNT);
        when(paymentRepository.findTopByTripIdOrderByCreatedAtDesc(TRIP_ID)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.refund(TRIP_ID, "corr-001");

        assertEquals(PaymentStatus.REFUNDED, response.getStatus());
        assertNotNull(response.getRefundedAt());
        ArgumentCaptor<LedgerEntry> ledger = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledger.capture());
        assertEquals(LedgerEntryType.REFUND, ledger.getValue().getEntryType());
        assertEquals(0, AMOUNT.negate().compareTo(ledger.getValue().getAmount()));
    }

    @Test
    @DisplayName("refund of a failed payment throws ConflictException")
    void refund_failedPayment_throwsConflictException() {
        when(paymentRepository.findTopByTripIdOrderByCreatedAtDesc(TRIP_ID))
                .thenReturn(Optional.of(existingPayment(PaymentStatus.FAILED, AMOUNT)));

        ConflictException ex = assertThrows(ConflictException.class, () -> paymentService.refund(TRIP_ID, "corr-001"));
        assertEquals("INVALID_REFUND_STATE", ex.getCode());
    }

    @Test
    @DisplayName("refund on non-existing trip payment throws ResourceNotFoundException")
    void refund_nonExistingPayment_throwsNotFound() {
        when(paymentRepository.findTopByTripIdOrderByCreatedAtDesc(TRIP_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> paymentService.refund(TRIP_ID, "corr-001"));
    }

    @Test
    @DisplayName("processTripCompleted with duplicate event does not process twice (idempotency)")
    void processTripCompleted_duplicateEvent_doesNotProcessTwice() {
        when(processedEventRepository.existsByEventId("evt-dup")).thenReturn(true);

        paymentService.processTripCompleted("evt-dup", TRIP_ID, PASSENGER_ID, AMOUNT, "VND", "corr-001");

        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    @DisplayName("processTripCompleted with new event opens the amount due and records the event, without paying it")
    void processTripCompleted_newEvent_createsPendingPayment() {
        when(processedEventRepository.existsByEventId("evt-new")).thenReturn(false);
        when(paymentRepository.findByIdempotencyKey("trip-" + TRIP_ID + "-completed")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentService.processTripCompleted("evt-new", TRIP_ID, PASSENGER_ID, AMOUNT, "VND", "corr-001");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertEquals(PASSENGER_ID, saved.getValue().getPassengerId());
        assertEquals(PaymentStatus.PENDING, saved.getValue().getStatus());
        verify(processedEventRepository).save(any());
        verifyNoInteractions(paymentEventProducer, paymentGatewaySimulator);
    }

    @Test
    @DisplayName("processTripCompleted re-published with a new eventId does not charge the trip twice")
    void processTripCompleted_tripAlreadyCharged_doesNotChargeTwice() {
        when(processedEventRepository.existsByEventId("evt-replay")).thenReturn(false);
        when(paymentRepository.findByIdempotencyKey("trip-" + TRIP_ID + "-completed"))
                .thenReturn(Optional.of(existingPayment(PaymentStatus.SUCCESS, AMOUNT)));

        paymentService.processTripCompleted("evt-replay", TRIP_ID, PASSENGER_ID, AMOUNT, "VND", "corr-001");

        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentEventProducer);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("payTrip declined by the simulator publishes PaymentFailed instead of throwing")
    void payTrip_declined_publishesPaymentFailed() {
        Payment due = existingPayment(PaymentStatus.PENDING, AMOUNT);
        when(paymentRepository.findByIdempotencyKey("trip-" + TRIP_ID + "-completed")).thenReturn(Optional.of(due));
        when(paymentRepository.lockById(due.getPaymentId())).thenReturn(Optional.of(due));
        when(paymentGatewaySimulator.isDeclined()).thenReturn(true);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.payTrip(TRIP_ID, PASSENGER_ID);

        assertEquals(PaymentStatus.FAILED, response.getStatus());
        verify(paymentEventProducer).publishPaymentFailed(any(Payment.class), eq(0), isNull());
        verify(paymentEventProducer, never()).publishPaymentSucceeded(any(), any());
    }

    @Test
    @DisplayName("payTrip by another passenger is refused and pays nothing")
    void payTrip_otherPassenger_forbidden() {
        Payment due = existingPayment(PaymentStatus.PENDING, AMOUNT);
        when(paymentRepository.findByIdempotencyKey("trip-" + TRIP_ID + "-completed")).thenReturn(Optional.of(due));
        when(paymentRepository.lockById(due.getPaymentId())).thenReturn(Optional.of(due));

        BusinessException error = assertThrows(BusinessException.class, () -> paymentService.payTrip(TRIP_ID, UUID.randomUUID()));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(paymentEventProducer, paymentGatewaySimulator);
    }

    @Test
    @DisplayName("payTrip before the trip is completed finds nothing due")
    void payTrip_nothingDue_notFound() {
        when(paymentRepository.findByIdempotencyKey("trip-" + TRIP_ID + "-completed")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> paymentService.payTrip(TRIP_ID, PASSENGER_ID));
    }
}

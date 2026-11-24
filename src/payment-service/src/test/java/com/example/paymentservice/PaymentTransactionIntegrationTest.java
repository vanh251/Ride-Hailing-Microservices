package com.example.paymentservice;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.entity.LedgerEntryType;
import com.example.paymentservice.entity.OutboxEvent;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.exception.PaymentDeclinedException;
import com.example.paymentservice.kafka.producer.PaymentEventProducer;
import com.example.paymentservice.repository.LedgerEntryRepository;
import com.example.paymentservice.repository.OutboxEventRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.example.paymentservice.repository.ProcessedEventRepository;
import com.example.paymentservice.service.PaymentGatewaySimulator;
import com.example.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Runs the real transactions against H2 to check what is committed, which mocked repositories cannot show.
 * Regression tests for the W2 review: a declined charge used to be rolled back after its event was sent.
 */
@SpringBootTest(properties = "payment.outbox.poll-interval-ms=3600000")
class PaymentTransactionIntegrationTest {

    private static final UUID PASSENGER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentGatewaySimulator paymentGatewaySimulator;

    @BeforeEach
    void cleanDatabase() {
        ledgerEntryRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void payosCannotBeRefundedBySimulator() {
        UUID tripId = UUID.randomUUID();
        Payment payment = Payment.builder().paymentId(UUID.randomUUID()).tripId(tripId)
                .passengerId(PASSENGER_ID).amount(new BigDecimal("36500")).currency("VND")
                .paymentMethod("PAYOS").status(PaymentStatus.SUCCESS)
                .idempotencyKey("payos-refund").correlationId("payos-refund").build();
        paymentRepository.saveAndFlush(payment);
        assertThrows(com.example.paymentservice.exception.ConflictException.class,
                () -> paymentService.refund(tripId, "refund"));
        assertEquals(PaymentStatus.SUCCESS, paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus());
        assertEquals(0, ledgerEntryRepository.count());
    }
    private PaymentChargeRequest chargeRequest() {
        return PaymentChargeRequest.builder()
                .passengerId(PASSENGER_ID)
                .amount(new BigDecimal("36500"))
                .currency("VND")
                .build();
    }

    @Test
    @DisplayName("charge declined keeps the FAILED payment and its PaymentFailed outbox event after the 402")
    void charge_declined_commitsFailedPaymentAndOutboxEvent() {
        UUID tripId = UUID.randomUUID();
        when(paymentGatewaySimulator.isDeclined()).thenReturn(true);

        assertThrows(PaymentDeclinedException.class, () ->
                paymentService.charge(tripId, chargeRequest(), "idem-declined", "corr-001"));

        Payment payment = paymentRepository.findByIdempotencyKey("idem-declined").orElseThrow();
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        List<OutboxEvent> outbox = outboxEventRepository.findAll();
        assertEquals(1, outbox.size());
        assertEquals(PaymentEventProducer.PAYMENT_FAILED, outbox.get(0).getEventType());
        assertFalse(outbox.get(0).getPublished());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());

        // Retrying with the same key returns the same decline without a second payment
        assertThrows(PaymentDeclinedException.class, () ->
                paymentService.charge(tripId, chargeRequest(), "idem-declined", "corr-001"));
        assertEquals(1, paymentRepository.count());
    }

    @Test
    @DisplayName("charge succeeded stores payment, CAPTURE ledger entry and a full PaymentSucceeded envelope in one transaction")
    void charge_succeeded_commitsPaymentLedgerAndOutboxEnvelope() {
        UUID tripId = UUID.randomUUID();
        when(paymentGatewaySimulator.isDeclined()).thenReturn(false);

        paymentService.charge(tripId, chargeRequest(), "idem-ok", "corr-002");

        Payment payment = paymentRepository.findByIdempotencyKey("idem-ok").orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(LedgerEntryType.CAPTURE, ledgerEntryRepository.findByTripIdOrderByCreatedAtAsc(tripId).get(0).getEntryType());

        OutboxEvent outbox = outboxEventRepository.findAll().get(0);
        JsonNode envelope = objectMapper.readTree(outbox.getPayload());
        assertEquals(outbox.getEventId().toString(), envelope.path("eventId").asText());
        assertEquals("PaymentSucceeded", envelope.path("eventType").asText());
        assertEquals("corr-002", envelope.path("correlationId").asText());
        assertEquals(tripId.toString(), envelope.path("payload").path("tripId").asText());
        assertEquals(PASSENGER_ID.toString(), envelope.path("payload").path("passengerId").asText());
    }

    @Test
    @DisplayName("processTripCompleted with the same trip twice (different eventIds) charges once")
    void processTripCompleted_sameTripTwice_chargesOnce() {
        UUID tripId = UUID.randomUUID();
        when(paymentGatewaySimulator.isDeclined()).thenReturn(false);

        paymentService.processTripCompleted("evt-a", tripId, PASSENGER_ID, new BigDecimal("47500"), "VND", "corr-003");
        paymentService.processTripCompleted("evt-b", tripId, PASSENGER_ID, new BigDecimal("47500"), "VND", "corr-003");
        paymentService.processTripCompleted("evt-a", tripId, PASSENGER_ID, new BigDecimal("47500"), "VND", "corr-003");

        assertEquals(1, paymentRepository.count());
        assertEquals(1, outboxEventRepository.count());
        assertEquals(2, processedEventRepository.count());
    }
}

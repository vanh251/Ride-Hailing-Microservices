package com.example.paymentservice.service.impl;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.dto.response.PaymentResponse;
import com.example.paymentservice.entity.LedgerEntry;
import com.example.paymentservice.entity.LedgerEntryType;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.entity.ProcessedEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    public static final String DEFAULT_CURRENCY = "VND";
    private static final String TRIP_COMPLETED_EVENT_TYPE = "TripCompleted";
    private static final String MOCK_PAYMENT_METHOD = "MOCK_CARD";
    // A simulated decline is final for that attempt; retryCount only counts infrastructure retries
    private static final int NO_RETRY = 0;

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentGatewaySimulator paymentGatewaySimulator;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.paymentservice.payos.PayosCheckoutService payosCheckoutService;

    @Override
    public void processPayosTripCompleted(UUID tripId) {
        payosCheckoutService.pendingFromCompletedTrip(tripId);
    }

    // noRollbackFor: a declined charge must keep its FAILED payment and PaymentFailed outbox event
    @Override
    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public PaymentResponse charge(UUID tripId, PaymentChargeRequest request, String idempotencyKeyHeader, String correlationId) {
        if (paymentRepository.existsByTripIdAndPaymentMethod(tripId, "PAYOS"))
            throw new ConflictException("payOS trips must use hosted checkout", "WRONG_PAYMENT_PROVIDER");
        String idempotencyKey = resolveIdempotencyKey(request, idempotencyKeyHeader);
        String resolvedCorrelationId = resolveCorrelationId(correlationId);
        log.info("Processing charge: tripId={}, idempotencyKey={}, correlationId={}",
                tripId, idempotencyKey, resolvedCorrelationId);

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayCharge(existing.get(), tripId, request, idempotencyKey);
        }

        // BR-05: a trip is charged once, even when the client retries with a new idempotency key
        if (paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCESS)) {
            throw new ConflictException("Trip " + tripId + " has already been paid", "PAYMENT_ALREADY_CAPTURED");
        }

        Payment payment = processCharge(tripId, request.getPassengerId(), request.getAmount(),
                resolveCurrency(request.getCurrency()), idempotencyKey, resolvedCorrelationId, null);
        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new PaymentDeclinedException("Payment charge failed: " + payment.getFailureReason());
        }
        return mapToResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTripId(UUID tripId) {
        Payment payment = paymentRepository.findTopByTripIdOrderByCreatedAtDesc(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for trip " + tripId));
        return mapToResponse(payment);
    }

    @Override
    @Transactional
    public PaymentResponse refund(UUID tripId, String correlationId) {
        log.info("Processing refund: tripId={}, correlationId={}", tripId, correlationId);
        Payment payment = paymentRepository.findTopByTripIdOrderByCreatedAtDesc(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for trip " + tripId));

        if ("PAYOS".equals(payment.getPaymentMethod()))
            throw new ConflictException("payOS refunds are not supported by the simulator", "PAYOS_REFUND_UNSUPPORTED");

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment already refunded: paymentId={}, tripId={}", payment.getPaymentId(), tripId);
            return mapToResponse(payment);
        }
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new ConflictException("Cannot refund payment with status: " + payment.getStatus(), "INVALID_REFUND_STATE");
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(Instant.now());
        Payment saved = paymentRepository.save(payment);
        recordLedgerEntry(saved, LedgerEntryType.REFUND, saved.getAmount().negate(), "Refund for trip " + tripId);

        log.info("Payment refunded: paymentId={}, tripId={}, amount={}", saved.getPaymentId(), tripId, saved.getAmount());
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void processTripCompleted(String eventId, UUID tripId, UUID passengerId, BigDecimal finalFare,
                                     String currency, String correlationId) {
        // Deduplication by eventId (CODING_CONVENTION §21.1)
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("Duplicate event detected, skipping: eventId={}, eventType={}, tripId={}",
                    eventId, TRIP_COMPLETED_EVENT_TYPE, tripId);
            return;
        }

        // A TripCompleted re-published with a new eventId must not charge the trip a second time
        String idempotencyKey = tripCompletedIdempotencyKey(tripId);
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent() || paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCESS)) {
            log.warn("Trip already has a payment, skipping charge: eventId={}, tripId={}, correlationId={}",
                    eventId, tripId, correlationId);
        } else {
            processCharge(tripId, passengerId, finalFare, resolveCurrency(currency), idempotencyKey, correlationId, eventId);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(TRIP_COMPLETED_EVENT_TYPE)
                .processedAt(Instant.now())
                .build());
    }

    /**
     * Charges through the simulated gateway and stores the payment, its ledger entry and the
     * PaymentSucceeded / PaymentFailed outbox event in the caller's transaction.
     */
    private Payment processCharge(UUID tripId, UUID passengerId, BigDecimal amount, String currency,
                                  String idempotencyKey, String correlationId, String causationId) {
        UUID paymentId = UUID.randomUUID();
        boolean declined = paymentGatewaySimulator.isDeclined();

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .tripId(tripId)
                .passengerId(passengerId)
                .amount(amount)
                .currency(currency)
                .paymentMethod(MOCK_PAYMENT_METHOD)
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .build();

        if (declined) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(PaymentGatewaySimulator.SIMULATED_FAILURE);
            Payment saved = paymentRepository.saveAndFlush(payment);
            paymentEventProducer.publishPaymentFailed(saved, NO_RETRY, causationId);
            log.warn("Payment declined: paymentId={}, tripId={}, amount={}, reason={}, correlationId={}",
                    paymentId, tripId, amount, saved.getFailureReason(), correlationId);
            return saved;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setGatewayRef("GW-" + UUID.randomUUID());
        payment.setCapturedAt(Instant.now());
        // saveAndFlush surfaces a duplicate idempotency key here instead of at commit time
        Payment saved = paymentRepository.saveAndFlush(payment);
        recordLedgerEntry(saved, LedgerEntryType.CAPTURE, amount, "Charge for trip " + tripId);
        paymentEventProducer.publishPaymentSucceeded(saved, causationId);
        log.info("Payment captured: paymentId={}, tripId={}, amount={} {}, correlationId={}",
                paymentId, tripId, amount, currency, correlationId);
        return saved;
    }

    // Repeating a charge with the same idempotency key returns the first result instead of charging again
    private PaymentResponse replayCharge(Payment existing, UUID tripId, PaymentChargeRequest request, String idempotencyKey) {
        if (!existing.getTripId().equals(tripId) || existing.getAmount().compareTo(request.getAmount()) != 0) {
            throw new ConflictException("Idempotency key already used for a different transaction", "IDEMPOTENCY_CONFLICT");
        }
        log.info("Duplicate idempotency key, returning first result: idempotencyKey={}, paymentId={}, status={}",
                idempotencyKey, existing.getPaymentId(), existing.getStatus());
        if (existing.getStatus() == PaymentStatus.FAILED) {
            throw new PaymentDeclinedException("Payment charge failed: " + existing.getFailureReason());
        }
        return mapToResponse(existing);
    }

    private void recordLedgerEntry(Payment payment, LedgerEntryType entryType, BigDecimal amount, String description) {
        ledgerEntryRepository.save(LedgerEntry.builder()
                .ledgerId(UUID.randomUUID())
                .payment(payment)
                .tripId(payment.getTripId())
                .entryType(entryType)
                .amount(amount)
                .currency(payment.getCurrency())
                .description(description)
                .build());
    }

    // X-Idempotency-Key is required for a charge (docs/api.md §8); the body field is accepted as a fallback
    private String resolveIdempotencyKey(PaymentChargeRequest request, String headerKey) {
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            return request.getIdempotencyKey();
        }
        throw new BusinessException("X-Idempotency-Key header is required to charge a payment",
                HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
    }

    // payments.correlation_id is NOT NULL; a direct call without X-Correlation-ID still gets a traceable id
    private String resolveCorrelationId(String correlationId) {
        return correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();
    }

    private String resolveCurrency(String currency) {
        return currency != null && !currency.isBlank() ? currency : DEFAULT_CURRENCY;
    }

    private static String tripCompletedIdempotencyKey(UUID tripId) {
        return "trip-" + tripId + "-completed";
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .tripId(payment.getTripId())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .processedAt(payment.getCapturedAt() != null ? payment.getCapturedAt() : payment.getCreatedAt())
                .refundedAt(payment.getRefundedAt())
                .failureReason(payment.getFailureReason())
                .build();
    }
}

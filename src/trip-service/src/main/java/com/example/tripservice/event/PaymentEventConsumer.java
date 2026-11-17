package com.example.tripservice.event;

import com.example.tripservice.service.TripService;
import com.example.tripservice.entity.ProcessedEvent;
import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.exception.ValidationException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static com.example.tripservice.event.EventValues.*;

@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final TripService tripService;

    private final ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "${spring.kafka.consumer.group-id:trip-service-group}")
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Map<String, Object>> e;
        try {
            e = objectMapper.readValue(record.value(), new TypeReference<EventEnvelope<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            throw new ValidationException("Malformed payment event", ex);
        }
        if (e == null)
            throw new ValidationException("Event cannot be null");
        boolean success = "PaymentSucceeded".equals(e.getEventType());
        if (!success && !"PaymentFailed".equals(e.getEventType()))
            return;
        if (e.getEventId() != null && processedEventRepository.existsById(e.getEventId().toString()))
            return;
        validate(e);
        var p = e.getPayload();
        UUID tripId = uuid(p, "tripId"), paymentId = uuid(p, "paymentId"), passengerId = uuid(p, "passengerId");
        if (!"Payment".equals(e.getAggregateType()) || !paymentId.toString().equals(e.getAggregateId()))
            throw new ValidationException("Payment aggregate mismatch");
        number(p, "amount");
        BigDecimal amount = new BigDecimal(p.get("amount").toString());
        String currency = text(p, "currency"), reason = success ? null : text(p, "reason");
        if (amount.signum() <= 0 || !"VND".equals(currency))
            throw new ValidationException("Invalid payment amount/currency");
        time(p, success ? "processedAt" : "failedAt");
        if (!success) {
            double retries = number(p, "retryCount");
            if (retries < 0 || retries != Math.rint(retries))
                throw new ValidationException("Invalid retryCount");
        }
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", e.getCorrelationId());
        try {
            tripService.applyPaymentResult(tripId, paymentId, passengerId, amount, currency, success, reason);
            processedEventRepository.save(ProcessedEvent.builder()
                .eventId(e.getEventId().toString())
                .eventType(e.getEventType())
                .processedAt(Instant.now())
                .build());
        }
        finally {
            if (previous == null)
                MDC.remove("correlationId");
            else
                MDC.put("correlationId", previous);
        }
    }

}

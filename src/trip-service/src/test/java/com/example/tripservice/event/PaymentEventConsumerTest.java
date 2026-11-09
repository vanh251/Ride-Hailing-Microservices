package com.example.tripservice.event;

import com.example.tripservice.service.TripService;
import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.exception.ValidationException;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentEventConsumerTest {

    @Test
    void replayIsSkipped_andMalformedEnvelopeIsRejected() {
        var trips = mock(TripService.class);
        var processed = mock(ProcessedEventRepository.class);
        var consumer = new PaymentEventConsumer(trips, processed, new ObjectMapper());
        String event = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentSucceeded\"}";
        when(processed.existsById(anyString())).thenReturn(true);
        consumer.onPaymentEvent(new ConsumerRecord<>("payment-events", 0, 0, "trip", event));
        verifyNoInteractions(trips);
        reset(processed);
        assertThrows(ValidationException.class,
                () -> consumer.onPaymentEvent(new ConsumerRecord<>("payment-events", 0, 0, "trip", event)));
        verify(processed, never()).save(any());
    }

    @Test
    void quotedMoneyIsRejectedBeforeBusinessWrite() {
        var trips = mock(TripService.class);
        var processed = mock(ProcessedEventRepository.class);
        var mapper = new ObjectMapper();
        UUID payment = UUID.randomUUID(), trip = UUID.randomUUID();
        var event = EventEnvelope.builder()
            .eventId(UUID.randomUUID())
            .eventType("PaymentSucceeded")
            .aggregateId(payment.toString())
            .aggregateType("Payment")
            .producer("payment-service")
            .correlationId(UUID.randomUUID().toString())
            .occurredAt(java.time.Instant.now())
            .payload(java.util.Map.of("tripId", trip, "paymentId", payment, "passengerId", UUID.randomUUID(), "amount",
                    "47500", "currency", "VND", "processedAt", java.time.Instant.now()))
            .build();
        var record = new ConsumerRecord<String, String>("payment-events", 0, 0, trip.toString(),
                mapper.writeValueAsString(event));
        assertThrows(ValidationException.class,
                () -> new PaymentEventConsumer(trips, processed, mapper).onPaymentEvent(record));
        verifyNoInteractions(trips);
        verify(processed, never()).save(any());
    }

}

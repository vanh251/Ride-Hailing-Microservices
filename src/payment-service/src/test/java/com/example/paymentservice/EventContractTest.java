package com.example.paymentservice;

import com.example.paymentservice.entity.OutboxEvent;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.kafka.consumer.TripEventConsumer;
import com.example.paymentservice.kafka.producer.PaymentEventProducer;
import com.example.paymentservice.repository.OutboxEventRepository;
import com.example.paymentservice.service.PaymentGatewaySimulator;
import com.example.paymentservice.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Contract tests against the shared fixtures in tests/data/events (docs/events.md v1.1).
 * They fail when this service and the fixture disagree on the fields of an event.
 */
@ExtendWith(MockitoExtension.class)
class EventContractTest {

    private static final Path FIXTURES = Path.of("..", "..", "tests", "data", "events");

    private final ObjectMapper objectMapper = new JsonMapper();

    @Mock
    private PaymentService paymentService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private String fixture(String eventType) throws IOException {
        return Files.readString(FIXTURES.resolve(eventType + ".json"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    @Test
    @DisplayName("consumeTripEvent accepts the TripCompleted fixture and charges its passenger and finalFare")
    void consumeTripEvent_tripCompletedFixture_chargesFixtureValues() throws IOException {
        String message = fixture("TripCompleted");
        JsonNode event = objectMapper.readTree(message);
        JsonNode payload = event.path("payload");
        TripEventConsumer consumer = new TripEventConsumer(paymentService, objectMapper);

        consumer.consumeTripEvent(new ConsumerRecord<>(TripEventConsumer.TRIP_EVENTS_TOPIC, 0, 0L,
                payload.path("tripId").asText(), message), acknowledgment);

        verify(paymentService).processTripCompleted(
                eq(event.path("eventId").asText()),
                eq(UUID.fromString(payload.path("tripId").asText())),
                eq(UUID.fromString(payload.path("passengerId").asText())),
                eq(payload.path("finalFare").decimalValue()),
                eq(payload.path("currency").asText()),
                eq(event.path("correlationId").asText()));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("publishPaymentSucceeded writes an envelope and payload with exactly the fields of the PaymentSucceeded fixture")
    void publishPaymentSucceeded_matchesFixtureFields() throws IOException {
        JsonNode published = publish(producer -> producer.publishPaymentSucceeded(payment(PaymentStatus.SUCCESS), "cause"));
        JsonNode expected = objectMapper.readTree(fixture("PaymentSucceeded"));

        assertEquals(fieldNames(expected), fieldNames(published), "envelope fields");
        assertEquals(fieldNames(expected.path("payload")), fieldNames(published.path("payload")), "payload fields");
        assertEquals("PaymentSucceeded", published.path("eventType").asText());
        assertEquals("cause", published.path("causationId").asText());
    }

    @Test
    @DisplayName("publishPaymentFailed writes an envelope and payload with exactly the fields of the PaymentFailed fixture")
    void publishPaymentFailed_matchesFixtureFields() throws IOException {
        Payment failed = payment(PaymentStatus.FAILED);
        failed.setFailureReason(PaymentGatewaySimulator.SIMULATED_FAILURE);
        JsonNode published = publish(producer -> producer.publishPaymentFailed(failed, 0, "cause"));
        JsonNode expected = objectMapper.readTree(fixture("PaymentFailed"));

        assertEquals(fieldNames(expected), fieldNames(published), "envelope fields");
        assertEquals(fieldNames(expected.path("payload")), fieldNames(published.path("payload")), "payload fields");
        assertEquals("PaymentFailed", published.path("eventType").asText());
    }

    private JsonNode publish(java.util.function.Consumer<PaymentEventProducer> action) {
        action.accept(new PaymentEventProducer(outboxEventRepository, objectMapper));
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        return objectMapper.readTree(outbox.getValue().getPayload());
    }

    private static Payment payment(PaymentStatus status) {
        return Payment.builder()
                .paymentId(UUID.randomUUID())
                .tripId(UUID.randomUUID())
                .passengerId(UUID.randomUUID())
                .amount(new BigDecimal("47500"))
                .currency("VND")
                .status(status)
                .correlationId("corr")
                .idempotencyKey("key")
                .capturedAt(status == PaymentStatus.SUCCESS ? Instant.now() : null)
                .build();
    }
}

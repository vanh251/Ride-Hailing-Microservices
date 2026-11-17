package com.example.driverservice.kafka.producer;

import com.example.driverservice.exception.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

    private static final String EVENT_ID = "e0000000-0000-4000-8000-000000000005";
    private static final String CORRELATION_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new JsonMapper();

    private DeadLetterPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DeadLetterPublisher(kafkaTemplate, objectMapper, "driver-service-group");
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("accept after retries sends the docs/events.md §6 DLQ message to <topic>.DLQ with the consumer group header")
    void accept_afterRetries_publishesDeadLetterMessage() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("trip-events", 2, 123L, "trip-001",
                "{\"eventId\":\"%s\",\"eventType\":\"TripCompleted\",\"correlationId\":\"%s\"}".formatted(EVENT_ID, CORRELATION_ID));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        Exception failure = new ListenerExecutionFailedException("listener failed", new IllegalStateException("database down"));
        for (int attempt = 1; attempt <= 4; attempt++) {
            publisher.failedDelivery(record, failure, attempt);
        }
        publisher.accept(record, failure);

        ProducerRecord<String, String> sent = sentRecord();
        assertEquals("trip-events.DLQ", sent.topic());
        assertEquals("trip-001", sent.key());
        assertEquals("driver-service-group", new String(
                sent.headers().lastHeader(DeadLetterPublisher.CONSUMER_GROUP_HEADER).value(), StandardCharsets.UTF_8));
        JsonNode message = objectMapper.readTree(sent.value());
        assertEquals("trip-events", message.path("originalTopic").asText());
        assertEquals(2, message.path("originalPartition").asInt());
        assertEquals(123L, message.path("originalOffset").asLong());
        assertEquals(EVENT_ID, message.path("originalMessage").path("eventId").asText());
        assertEquals("IllegalStateException: database down", message.path("error").asText());
        assertEquals(3, message.path("retryCount").asInt());
        assertEquals(CORRELATION_ID, message.path("correlationId").asText());
        assertFalse(message.path("firstFailedAt").isMissingNode());
        assertFalse(message.path("lastFailedAt").isMissingNode());
    }

    @Test
    @DisplayName("accept of a non-retryable record keeps raw text and reports retryCount 0")
    void accept_notRetryableInvalidJson_keepsRawMessage() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("trip-events", 0, 7L, null, "not-json");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher.accept(record, new InvalidEventException("Message on trip-events is not valid JSON"));

        ProducerRecord<String, String> sent = sentRecord();
        assertNull(sent.key());
        JsonNode message = objectMapper.readTree(sent.value());
        assertEquals("not-json", message.path("originalMessage").asText());
        assertEquals(0, message.path("retryCount").asInt());
    }

    @Test
    @DisplayName("accept rethrows when the DLQ send fails so the offset is not committed")
    void accept_dlqSendFails_throws() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("trip-events", 0, 8L, "trip-001", "{}");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThrows(IllegalStateException.class, () -> publisher.accept(record, new RuntimeException("boom")));
    }
}

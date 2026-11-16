package com.example.driverservice.kafka.producer;

import com.example.driverservice.kafka.event.DeadLetterMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Sends a record that failed all retries to {@code <original-topic>.DLQ} in the format of docs/events.md §6.
 * Also listens to the error handler's retries to know when the record failed first and how often.
 * Same behaviour as the DeadLetterPublisher of payment-service, so scripts/kafka/tail-dlq.sh and replay-dlq.sh
 * work for this consumer group too.
 */
@Slf4j
@Component
public class DeadLetterPublisher implements ConsumerRecordRecoverer, RetryListener {

    public static final String DLQ_SUFFIX = ".DLQ";
    public static final String CONSUMER_GROUP_HEADER = "x-consumer-group";
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String consumerGroup;

    private final Map<String, FailureHistory> failures = new ConcurrentHashMap<>();

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                               @Value("${spring.kafka.consumer.group-id:}") String consumerGroup) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        failures.compute(recordKey(record), (key, history) -> history == null
                ? new FailureHistory(Instant.now(), deliveryAttempt)
                : new FailureHistory(history.firstFailedAt(), deliveryAttempt));
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        FailureHistory history = failures.remove(recordKey(record));
        Instant now = Instant.now();
        JsonNode originalMessage = parseOriginal(record);
        Throwable rootCause = rootCause(exception);

        DeadLetterMessage message = DeadLetterMessage.builder()
                .originalTopic(record.topic())
                .originalPartition(record.partition())
                .originalOffset(record.offset())
                .originalMessage(originalMessage)
                .error(rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage())
                // deliveryAttempt counts the first try, retries are the attempts after it
                .retryCount(history == null ? 0 : Math.max(0, history.attempts() - 1))
                .firstFailedAt(history == null ? now : history.firstFailedAt())
                .lastFailedAt(now)
                .correlationId(originalMessage == null ? null : originalMessage.path("correlationId").asText(null))
                .build();

        String dlqTopic = record.topic() + DLQ_SUFFIX;
        String key = record.key() == null ? null : record.key().toString();
        try {
            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, objectMapper.writeValueAsString(message));
            // Several consumer groups read the same topic: say which one gave up on the record
            dlqRecord.headers().add(CONSUMER_GROUP_HEADER, consumerGroup.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(dlqRecord).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending record to " + dlqTopic, e);
        } catch (Exception e) {
            // Rethrow so the error handler does not commit the offset and the record is delivered again
            throw new IllegalStateException("Failed to send record to " + dlqTopic, e);
        }
        log.error("Moved record to DLQ: dlqTopic={}, consumerGroup={}, partition={}, offset={}, retryCount={}, error={}, correlationId={}",
                dlqTopic, consumerGroup, record.partition(), record.offset(), message.getRetryCount(),
                message.getError(), message.getCorrelationId());
    }

    private JsonNode parseOriginal(ConsumerRecord<?, ?> record) {
        if (record.value() == null) {
            return null;
        }
        String value = record.value().toString();
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException e) {
            // Not JSON: keep the raw text so nothing is lost
            return objectMapper.getNodeFactory().stringNode(value);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String recordKey(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }

    private record FailureHistory(Instant firstFailedAt, int attempts) {
    }
}

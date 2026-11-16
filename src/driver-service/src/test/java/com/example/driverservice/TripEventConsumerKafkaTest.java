package com.example.driverservice;

import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.kafka.producer.DeadLetterPublisher;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import com.example.driverservice.service.DriverService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer wired as in production (listener container, manual ack, error handler, DLQ publisher) against an
 * embedded Kafka broker and a real database (H2 in PostgreSQL mode). The other consumer tests call the classes
 * directly; this one checks that an event published to trip-events really reaches them.
 */
@SpringBootTest(properties = {
        // src/test/resources/application.yml excludes the DataSource and keeps the listener off; this class needs both
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:driver_kafka;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(partitions = 1, topics = {"trip-events", "trip-events.DLQ"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class TripEventConsumerKafkaTest {

    private static final String TOPIC = "trip-events";
    private static final String DLQ_TOPIC = "trip-events.DLQ";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private DriverService driverService;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Driver reservedDriver(UUID tripId) {
        Driver driver = driverRepository.saveAndFlush(Driver.builder()
                .driverId(UUID.randomUUID())
                .fullName("Tran Van Binh")
                .licenseNumber("012345678901")
                .status(DriverStatus.AVAILABLE)
                .build());
        driverService.reserveDriver(driver.getDriverId(), tripId, driver.getVersion());
        return driver;
    }

    private Driver reload(Driver driver) {
        return driverRepository.findById(driver.getDriverId()).orElseThrow();
    }

    private static String event(UUID eventId, String eventType, UUID tripId, String payload) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "aggregateId": "%s",
                  "aggregateType": "Trip",
                  "producer": "trip-service",
                  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "causationId": null,
                  "schemaVersion": "1.0",
                  "occurredAt": "2026-09-30T10:00:06Z",
                  "payload": %s
                }
                """.formatted(eventId, eventType, tripId, payload);
    }

    private void publish(String key, String value) throws Exception {
        kafkaTemplate.send(TOPIC, key, value).get(10, TimeUnit.SECONDS);
    }

    // Everything on trip-events.DLQ so far, read by a consumer group of its own
    private List<ConsumerRecord<String, String>> deadLetters() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLQ_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    @Test
    @DisplayName("Events published to trip-events drive the driver RESERVED -> BUSY -> AVAILABLE")
    void tripEvents_assignedThenCompleted_driverBecomesBusyThenAvailable() throws Exception {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        UUID assignedEventId = UUID.randomUUID();
        UUID completedEventId = UUID.randomUUID();

        // RideRequested is not for this service and must not get in the way
        publish(tripId.toString(), event(UUID.randomUUID(), "RideRequested", tripId, "{\"tripId\": \"%s\"}".formatted(tripId)));
        publish(tripId.toString(), event(assignedEventId, "DriverAssigned", tripId,
                "{\"tripId\": \"%s\", \"driverId\": \"%s\"}".formatted(tripId, driver.getDriverId())));

        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(DriverStatus.BUSY, reload(driver).getStatus()));
        Driver busy = reload(driver);
        assertEquals(tripId, busy.getReservedForTrip());
        assertNull(busy.getReservedUntil());
        assertTrue(processedEventRepository.existsById(assignedEventId));

        // The payload trip-service really sends: driverId may be empty, passengerId is absent
        publish(tripId.toString(), event(completedEventId, "TripCompleted", tripId,
                "{\"tripId\": \"%s\", \"driverId\": \"\", \"finalFare\": 47500}".formatted(tripId)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(DriverStatus.AVAILABLE, reload(driver).getStatus()));
        assertNull(reload(driver).getReservedForTrip());
        assertTrue(processedEventRepository.existsById(completedEventId));
        assertEquals(2, processedEventRepository.findAllById(List.of(assignedEventId, completedEventId)).size());
    }

    @Test
    @DisplayName("A malformed event goes to trip-events.DLQ without retry, and the events after it are still consumed")
    void tripEvents_malformedEvent_goesToDlqAndConsumerContinues() throws Exception {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        String malformed = event(UUID.randomUUID(), "DriverAssigned", tripId, "{\"driverId\": \"%s\"}".formatted(driver.getDriverId()));

        publish(tripId.toString(), malformed);
        publish(tripId.toString(), event(UUID.randomUUID(), "TripCancelled", tripId,
                "{\"tripId\": \"%s\", \"reason\": \"PASSENGER_CANCELLED\"}".formatted(tripId)));

        // The cancellation behind the bad record is processed: the consumer was not stuck on it
        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(DriverStatus.AVAILABLE, reload(driver).getStatus()));

        List<ConsumerRecord<String, String>> deadLetters = deadLetters().stream()
                .filter(record -> tripId.toString().equals(record.key()))
                .toList();
        assertEquals(1, deadLetters.size(), "dead letters of this trip");
        ConsumerRecord<String, String> deadLetter = deadLetters.get(0);
        assertEquals("driver-service-group", new String(
                deadLetter.headers().lastHeader(DeadLetterPublisher.CONSUMER_GROUP_HEADER).value(), StandardCharsets.UTF_8));
        JsonNode message = objectMapper.readTree(deadLetter.value());
        assertEquals(TOPIC, message.path("originalTopic").asText());
        assertEquals("DriverAssigned", message.path("originalMessage").path("eventType").asText());
        assertEquals(0, message.path("retryCount").asInt());
        assertTrue(message.path("error").asText().startsWith("InvalidEventException"), message.path("error").asText());
    }
}

package com.example.notificationservice;

import com.example.notificationservice.dto.NotificationDto;
import com.example.notificationservice.dto.RecipientType;
import com.example.notificationservice.kafka.consumer.NotificationEventConsumer;
import com.example.notificationservice.service.NotificationService;
import com.example.notificationservice.service.TripParticipantRegistry;
import com.example.notificationservice.service.impl.NotificationServiceImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Feeds every shared fixture of tests/data/events (docs/events.md v1.1) to the consumer
 * and checks who is notified, as listed in the "Consumer Actions" of each event.
 */
class EventContractTest {

    private static final Path FIXTURES = Path.of("..", "..", "tests", "data", "events");
    private static final String PASSENGER = "a0000000-0000-4000-8000-000000000001";
    private static final String DRIVER = "d0000000-0000-4000-8000-000000000001";

    private NotificationService notificationService;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl();
        consumer = new NotificationEventConsumer(notificationService, new TripParticipantRegistry(), new JsonMapper());
    }

    private void consume(String topic, String eventType) throws IOException {
        String message = Files.readString(FIXTURES.resolve(eventType + ".json"));
        consumer.consumeDomainEvent(new ConsumerRecord<>(topic, 0, 0L, null, message), mock(Acknowledgment.class));
    }

    @ParameterizedTest(name = "{1} notifies {2}")
    @DisplayName("consumeDomainEvent notifies the recipients of docs/events.md for each fixture")
    @CsvSource({
            "trip-events,     RideRequested,    PASSENGER",
            "matching-events, DriverReserved,   ''",
            "trip-events,     DriverAssigned,   ''",
            "driver-events,   DriverAssignmentConfirmed, PASSENGER DRIVER",
            "driver-events,   DriverAssignmentRejected,  ''",
            "trip-events,     TripStarted,      PASSENGER",
            "trip-events,     TripCompleted,    PASSENGER",
            "payment-events,  PaymentSucceeded, PASSENGER DRIVER",
            "payment-events,  PaymentFailed,    PASSENGER",
            "trip-events,     TripCancelled,    PASSENGER DRIVER"
    })
    void consumeDomainEvent_fixture_notifiesExpectedRecipients(String topic, String eventType, String recipients) throws IOException {
        // PaymentSucceeded names no driver, and a cancelled ASSIGNED trip had one: known from the confirmation
        if (eventType.equals("PaymentSucceeded") || eventType.equals("TripCancelled")) {
            consume("driver-events", "DriverAssignmentConfirmed");
        }
        int before = notificationService.getRecentNotifications().size();

        consume(topic, eventType);

        List<NotificationDto> sent = notificationService.getRecentNotifications()
                .subList(0, notificationService.getRecentNotifications().size() - before);
        Set<String> actual = sent.stream()
                .map(n -> n.getRecipientType() + ":" + n.getRecipientId())
                .collect(Collectors.toSet());
        Set<String> expected = Set.of(recipients.split(" ")).stream()
                .filter(type -> !type.isBlank())
                .map(type -> type + ":" + (RecipientType.valueOf(type) == RecipientType.PASSENGER ? PASSENGER : DRIVER))
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }
}

package com.example.notificationservice.kafka.consumer;

import com.example.notificationservice.exception.ValidationException;
import com.example.notificationservice.service.NotificationService;
import com.example.notificationservice.service.TripParticipantRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the domain events of docs/events.md §5 into passenger and driver notifications.
 * A trip has a driver only after DriverAssignmentConfirmed (docs/events.md §4.10): DriverReserved and
 * DriverAssigned are tentative and notify nobody, and a rejected attempt's driver is never remembered.
 * Notifications are best-effort: a missing recipient is logged and skipped. A malformed message
 * is not swallowed; the error handler in KafkaConsumerConfig retries it and moves it to the DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    public static final String TRIP_EVENTS_TOPIC = "trip-events";
    public static final String MATCHING_EVENTS_TOPIC = "matching-events";
    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    public static final String DRIVER_EVENTS_TOPIC = "driver-events";
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String DEFAULT_CURRENCY = "VND";
    // Their driverId belongs to an attempt that may not be (or never was) confirmed
    private static final Set<String> TENTATIVE_DRIVER_EVENTS =
            Set.of("DriverReserved", "DriverAssigned", "DriverAssignmentRejected", "TripCancelled");

    private final NotificationService notificationService;
    private final TripParticipantRegistry participantRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {TRIP_EVENTS_TOPIC, MATCHING_EVENTS_TOPIC, PAYMENT_EVENTS_TOPIC, DRIVER_EVENTS_TOPIC},
            groupId = "${spring.kafka.consumer.group-id:notification-service-group}"
    )
    public void consumeDomainEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        JsonNode root = parse(record);
        String eventType = root.path("eventType").asText("");
        String eventId = root.path("eventId").asText("");
        String correlationId = root.path(CORRELATION_ID_KEY).asText("");
        if (!correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_KEY, correlationId);
        }
        try {
            JsonNode payload = root.path("payload");
            String tripId = payload.path("tripId").asText("");
            participantRegistry.remember(tripId, text(payload, "passengerId"),
                    TENTATIVE_DRIVER_EVENTS.contains(eventType) ? null : text(payload, "driverId"));
            log.info("Processing event: eventType={}, eventId={}, tripId={}, topic={}",
                    eventType, eventId, tripId, record.topic());

            switch (eventType) {
                case "RideRequested" -> handleRideRequested(payload, tripId, correlationId);
                case "DriverAssignmentConfirmed" -> handleDriverAssignmentConfirmed(payload, tripId, correlationId);
                case "TripStarted" -> handleTripStarted(payload, tripId, correlationId);
                case "TripCompleted" -> handleTripCompleted(payload, tripId, correlationId);
                case "PaymentSucceeded" -> handlePaymentSucceeded(payload, tripId, correlationId);
                case "PaymentFailed" -> handlePaymentFailed(payload, tripId, correlationId);
                case "TripCancelled" -> handleTripCancelled(payload, tripId, correlationId);
                default -> log.debug("Ignoring event type not handled by notification-service: {}", eventType);
            }
            acknowledgment.acknowledge();
        } finally {
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    private void handleRideRequested(JsonNode payload, String tripId, String correlationId) {
        notifyPassenger(payload, tripId, "RideRequested", correlationId,
                "Ride Requested", "Searching for a driver nearby...");
    }

    private void handleDriverAssignmentConfirmed(JsonNode payload, String tripId, String correlationId) {
        notifyPassenger(payload, tripId, "DriverAssignmentConfirmed", correlationId,
                "Driver Assigned", "Driver found! Your driver is heading to your pickup location.");
        notifyDriver(payload, tripId, "DriverAssignmentConfirmed", correlationId,
                "New Trip Assigned", "You have been assigned trip " + tripId);
    }

    private void handleTripStarted(JsonNode payload, String tripId, String correlationId) {
        notifyPassenger(payload, tripId, "TripStarted", correlationId,
                "Trip Started", "Your ride has started. Enjoy your journey!");
    }

    private void handleTripCompleted(JsonNode payload, String tripId, String correlationId) {
        notifyPassenger(payload, tripId, "TripCompleted", correlationId,
                "Trip Completed", "Trip completed. Final fare: " + formatAmount(payload.path("finalFare"), currency(payload))
                        + ". Processing payment...");
    }

    private void handlePaymentSucceeded(JsonNode payload, String tripId, String correlationId) {
        String amount = formatAmount(payload.path("amount"), currency(payload));
        notifyPassenger(payload, tripId, "PaymentSucceeded", correlationId,
                "Payment Succeeded", "Payment of " + amount + " was successful. Thank you for riding!");
        // PaymentSucceeded carries no driverId (docs/events.md §4.6): the driver is known from earlier events of the trip
        notifyDriver(payload, tripId, "PaymentSucceeded", correlationId,
                "Payment Received", "Payment of " + amount + " received for trip " + tripId);
    }

    private void handlePaymentFailed(JsonNode payload, String tripId, String correlationId) {
        notifyPassenger(payload, tripId, "PaymentFailed", correlationId,
                "Payment Failed", "Payment failed for trip " + tripId + ". Please retry or contact support.");
    }

    private void handleTripCancelled(JsonNode payload, String tripId, String correlationId) {
        String reason = payload.path("reason").asText("");
        String suffix = reason.isBlank() ? "" : ": " + reason;
        notifyPassenger(payload, tripId, "TripCancelled", correlationId,
                "Trip Cancelled", "Your trip was cancelled" + suffix);
        // Only a confirmed driver was told about the trip; an ASSIGNING trip's pending driver was not
        if ("ASSIGNED".equals(payload.path("previousStatus").asText(""))) {
            resolveDriver(payload, tripId).ifPresent(driverId -> notificationService.sendDriverNotification(
                    driverId, tripId, "Trip Cancelled", "Trip " + tripId + " was cancelled" + suffix, "TripCancelled", correlationId));
        }
    }

    private void notifyPassenger(JsonNode payload, String tripId, String eventType, String correlationId,
                                 String title, String message) {
        Optional<String> passengerId = Optional.ofNullable(text(payload, "passengerId"))
                .or(() -> participantRegistry.passengerOf(tripId));
        if (passengerId.isEmpty()) {
            log.warn("No passenger known for {} event, notification skipped: tripId={}", eventType, tripId);
            return;
        }
        notificationService.sendPassengerNotification(passengerId.get(), tripId, title, message, eventType, correlationId);
    }

    private void notifyDriver(JsonNode payload, String tripId, String eventType, String correlationId,
                              String title, String message) {
        Optional<String> driverId = resolveDriver(payload, tripId);
        if (driverId.isEmpty()) {
            log.warn("No driver known for {} event, notification skipped: tripId={}", eventType, tripId);
            return;
        }
        notificationService.sendDriverNotification(driverId.get(), tripId, title, message, eventType, correlationId);
    }

    private Optional<String> resolveDriver(JsonNode payload, String tripId) {
        return Optional.ofNullable(text(payload, "driverId")).or(() -> participantRegistry.driverOf(tripId));
    }

    private JsonNode parse(ConsumerRecord<String, String> record) {
        if (record.value() == null || record.value().isBlank()) {
            throw new ValidationException("Empty message on " + record.topic());
        }
        try {
            return objectMapper.readTree(record.value());
        } catch (JacksonException e) {
            throw new ValidationException("Message on " + record.topic() + " is not valid JSON", e);
        }
    }

    // Blank and JSON null both count as "not given"
    private static String text(JsonNode payload, String field) {
        String value = payload.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static String currency(JsonNode payload) {
        String currency = payload.path("currency").asText("");
        return currency.isBlank() ? DEFAULT_CURRENCY : currency;
    }

    // 47500.0 -> "47,500 VND"
    private static String formatAmount(JsonNode amount, String currency) {
        if (!amount.isNumber()) {
            return "an unknown amount";
        }
        BigDecimal value = amount.decimalValue().setScale(0, RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, "%,d %s", value.longValue(), currency);
    }
}

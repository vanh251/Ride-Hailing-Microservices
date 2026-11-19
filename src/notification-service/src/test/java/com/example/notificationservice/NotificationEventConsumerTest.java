package com.example.notificationservice;

import com.example.notificationservice.exception.ValidationException;
import com.example.notificationservice.kafka.consumer.NotificationEventConsumer;
import com.example.notificationservice.service.NotificationService;
import com.example.notificationservice.service.TripParticipantRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    private static final String TRIP_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PASSENGER_ID = "550e8400-e29b-41d4-a716-446655440001";
    private static final String DRIVER_ID = "d0000000-0000-4000-8000-000000000001";

    @Mock
    private NotificationService notificationService;

    @Mock
    private Acknowledgment acknowledgment;

    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationEventConsumer(notificationService, new TripParticipantRegistry(), new JsonMapper());
    }

    private void consume(String topic, String eventType, String payload) {
        String message = """
                {
                  "eventId": "evt-%s",
                  "eventType": "%s",
                  "correlationId": "corr-001",
                  "payload": %s
                }
                """.formatted(eventType, eventType, payload);
        consumer.consumeDomainEvent(new ConsumerRecord<>(topic, 0, 0L, TRIP_ID, message), acknowledgment);
    }

    @Test
    @DisplayName("consumeDomainEvent handles RideRequested and acknowledges the record")
    void consumeDomainEvent_rideRequested_sendsPassengerNotification() {
        consume("trip-events", "RideRequested", """
                {"tripId": "%s", "passengerId": "%s"}
                """.formatted(TRIP_ID, PASSENGER_ID));

        verify(notificationService).sendPassengerNotification(eq(PASSENGER_ID), eq(TRIP_ID), eq("Ride Requested"),
                anyString(), eq("RideRequested"), eq("corr-001"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeDomainEvent sends no assignment notice for the tentative DriverReserved and DriverAssigned")
    void consumeDomainEvent_reservedAndAssigned_notifyNobody() {
        consume("matching-events", "DriverReserved", """
                {"tripId": "%s", "driverId": "%s", "passengerId": "%s", "reservationVersion": 6}
                """.formatted(TRIP_ID, DRIVER_ID, PASSENGER_ID));
        consume("trip-events", "DriverAssigned", """
                {"tripId": "%s", "driverId": "%s", "passengerId": "%s", "reservationVersion": 6}
                """.formatted(TRIP_ID, DRIVER_ID, PASSENGER_ID));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("consumeDomainEvent notifies passenger and driver on DriverAssignmentConfirmed, passenger taken from RideRequested when absent")
    void consumeDomainEvent_assignmentConfirmed_notifiesPassengerAndDriver() {
        consume("trip-events", "RideRequested", """
                {"tripId": "%s", "passengerId": "%s"}
                """.formatted(TRIP_ID, PASSENGER_ID));
        consume("driver-events", "DriverAssignmentConfirmed", """
                {"tripId": "%s", "driverId": "%s", "reservationVersion": 6}
                """.formatted(TRIP_ID, DRIVER_ID));

        verify(notificationService).sendPassengerNotification(eq(PASSENGER_ID), eq(TRIP_ID), eq("Driver Assigned"),
                anyString(), eq("DriverAssignmentConfirmed"), eq("corr-001"));
        verify(notificationService).sendDriverNotification(eq(DRIVER_ID), eq(TRIP_ID), eq("New Trip Assigned"),
                anyString(), eq("DriverAssignmentConfirmed"), eq("corr-001"));
    }

    @Test
    @DisplayName("consumeDomainEvent never addresses the driver of a rejected attempt")
    void consumeDomainEvent_rejectedAttempt_driverIsNotRemembered() {
        String rejectedDriver = "d0000000-0000-4000-8000-000000000002";
        String attempt = """
                {"tripId": "%s", "driverId": "%s", "passengerId": "%s", "reservationVersion": 6}
                """.formatted(TRIP_ID, rejectedDriver, PASSENGER_ID);
        consume("matching-events", "DriverReserved", attempt);
        consume("trip-events", "DriverAssigned", attempt);
        consume("driver-events", "DriverAssignmentRejected", attempt);
        consume("trip-events", "TripCancelled", """
                {"tripId": "%s", "driverId": "%s", "passengerId": "%s", "previousStatus": "ASSIGNING"}
                """.formatted(TRIP_ID, rejectedDriver, PASSENGER_ID));
        consume("payment-events", "PaymentSucceeded", """
                {"tripId": "%s", "passengerId": "%s", "amount": 47500.0}
                """.formatted(TRIP_ID, PASSENGER_ID));

        verify(notificationService, never()).sendDriverNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("consumeDomainEvent notifies the driver of PaymentSucceeded (no driverId in its payload) from earlier trip events")
    void consumeDomainEvent_paymentSucceeded_sendsPassengerAndDriverNotifications() {
        consume("driver-events", "DriverAssignmentConfirmed", """
                {"tripId": "%s", "driverId": "%s", "passengerId": "%s"}
                """.formatted(TRIP_ID, DRIVER_ID, PASSENGER_ID));
        consume("payment-events", "PaymentSucceeded", """
                {"paymentId": "pay-001", "tripId": "%s", "passengerId": "%s", "amount": 47500.0, "currency": "VND"}
                """.formatted(TRIP_ID, PASSENGER_ID));

        verify(notificationService).sendPassengerNotification(eq(PASSENGER_ID), eq(TRIP_ID), eq("Payment Succeeded"),
                contains("47,500 VND"), eq("PaymentSucceeded"), eq("corr-001"));
        verify(notificationService).sendDriverNotification(eq(DRIVER_ID), eq(TRIP_ID), eq("Payment Received"),
                contains("47,500 VND"), eq("PaymentSucceeded"), eq("corr-001"));
    }

    @Test
    @DisplayName("consumeDomainEvent skips a notification whose recipient is unknown instead of sending it to an empty id")
    void consumeDomainEvent_unknownRecipient_skipsNotification() {
        consume("trip-events", "TripStarted", """
                {"tripId": "%s", "driverId": "%s"}
                """.formatted(TRIP_ID, DRIVER_ID));

        verify(notificationService, never()).sendPassengerNotification(any(), any(), any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeDomainEvent with invalid JSON throws ValidationException so the record goes to the DLQ")
    void consumeDomainEvent_invalidJson_throwsValidationException() {
        assertThrows(ValidationException.class, () -> consumer.consumeDomainEvent(
                new ConsumerRecord<>("trip-events", 0, 0L, TRIP_ID, "not-json"), acknowledgment));

        verifyNoInteractions(notificationService);
        verify(acknowledgment, never()).acknowledge();
    }
}

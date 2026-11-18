package com.example.tripservice.event;

import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.service.TripService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DriverAssignmentResultConsumerTest {
    @Mock TripService trips;
    @Mock ProcessedEventRepository processed;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks DriverAssignmentResultConsumer consumer;

    @Test
    void confirmedEventPassesExactReservationIdentity() {
        UUID trip = UUID.randomUUID(), driver = UUID.randomUUID(), command = UUID.randomUUID();
        consumer.onDriverOutcome(record(trip, driver, command, 2, "DriverAssignmentConfirmed"));
        verify(trips).applyAssignmentOutcome(trip, driver, 2, command, true);
        verify(processed).save(any());
    }

    @Test
    void rejectedEventDoesNotPretendToConfirm() {
        UUID trip = UUID.randomUUID(), driver = UUID.randomUUID(), command = UUID.randomUUID();
        consumer.onDriverOutcome(record(trip, driver, command, 2, "DriverAssignmentRejected"));
        verify(trips).applyAssignmentOutcome(trip, driver, 2, command, false);
    }

    @Test
    void malformedVersionIsRejectedBeforeStateChange() {
        UUID trip = UUID.randomUUID(), driver = UUID.randomUUID(), command = UUID.randomUUID();
        assertThrows(com.example.tripservice.exception.ValidationException.class,
            () -> consumer.onDriverOutcome(record(trip, driver, command, -1, "DriverAssignmentConfirmed")));
        verifyNoInteractions(trips);
    }

    private ConsumerRecord<String, String> record(UUID trip, UUID driver, UUID command, int version, String type) {
        String json = """
            {"eventId":"%s","eventType":"%s","aggregateId":"%s","aggregateType":"Trip",
             "producer":"driver-service","correlationId":"%s","causationId":"%s",
             "schemaVersion":"1.0","occurredAt":"2026-11-19T12:00:00Z",
             "payload":{"tripId":"%s","driverId":"%s","reservationVersion":%d,
                        "driverAssignedEventId":"%s"}}
            """.formatted(UUID.randomUUID(), type, trip, UUID.randomUUID(), command,
                trip, driver, version, command);
        return new ConsumerRecord<>("driver-events", 0, 0, trip.toString(), json);
    }
}

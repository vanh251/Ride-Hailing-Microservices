package com.example.driverservice.kafka.producer;

import com.example.driverservice.entity.OutboxEvent;
import com.example.driverservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverEventOutboxRelayTest {

    private static final String TOPIC = "driver-events";
    private static final String TRIP_A = "550e8400-e29b-41d4-a716-446655440000";
    private static final String TRIP_B = "660e8400-e29b-41d4-a716-446655440001";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private DriverEventOutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new DriverEventOutboxRelay(outboxEventRepository, kafkaTemplate, 1000);
    }

    private static OutboxEvent row(String tripId, String eventType, Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        return OutboxEvent.builder()
                .id(eventId)
                .aggregateId(tripId)
                .aggregateType("Trip")
                .eventType(eventType)
                .payload("{\"eventId\":\"%s\",\"eventType\":\"%s\",\"aggregateId\":\"%s\"}".formatted(eventId, eventType, tripId))
                .createdAt(createdAt)
                .build();
    }

    private static CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> brokerDown() {
        return CompletableFuture.failedFuture(new KafkaException("broker not available"));
    }

    @Test
    @DisplayName("An acknowledged event is published to driver-events with the tripId as key, then deleted")
    void publishPending_brokerAcknowledges_publishesStoredEnvelopeAndDeletesRow() {
        OutboxEvent confirmed = row(TRIP_A, "DriverAssignmentConfirmed", Instant.now());
        when(outboxEventRepository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(confirmed));
        when(kafkaTemplate.send(TOPIC, TRIP_A, confirmed.getPayload())).thenReturn(acknowledged());

        int published = relay.publishPending();

        assertEquals(1, published);
        verify(kafkaTemplate).send(TOPIC, TRIP_A, confirmed.getPayload());
        verify(outboxEventRepository).delete(confirmed);
    }

    @Test
    @DisplayName("Kafka down: the row is kept, and once Kafka is back the same envelope, same eventId, is published")
    void publishPending_brokerDownThenBack_keepsRowAndResendsSameEnvelope() {
        OutboxEvent confirmed = row(TRIP_A, "DriverAssignmentConfirmed", Instant.now());
        when(outboxEventRepository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(confirmed));
        when(kafkaTemplate.send(TOPIC, TRIP_A, confirmed.getPayload())).thenReturn(brokerDown(), acknowledged());

        assertEquals(0, relay.publishPending());
        verify(outboxEventRepository, never()).delete(any());

        assertEquals(1, relay.publishPending());
        verify(kafkaTemplate, times(2)).send(TOPIC, TRIP_A, confirmed.getPayload());
        verify(outboxEventRepository).delete(confirmed);
    }

    @Test
    @DisplayName("When an event of a trip fails, the later events of that trip wait; other trips are still published")
    void publishPending_earlierEventOfTripFails_laterEventsOfTripWait() {
        Instant now = Instant.now();
        OutboxEvent tripAFirst = row(TRIP_A, "DriverAssignmentRejected", now);
        OutboxEvent tripB = row(TRIP_B, "DriverAssignmentConfirmed", now.plusMillis(1));
        OutboxEvent tripASecond = row(TRIP_A, "DriverAssignmentConfirmed", now.plusMillis(2));
        when(outboxEventRepository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(tripAFirst, tripB, tripASecond));
        when(kafkaTemplate.send(TOPIC, TRIP_A, tripAFirst.getPayload())).thenReturn(brokerDown());
        when(kafkaTemplate.send(TOPIC, TRIP_B, tripB.getPayload())).thenReturn(acknowledged());

        int published = relay.publishPending();

        assertEquals(1, published);
        verify(kafkaTemplate, never()).send(eq(TOPIC), eq(TRIP_A), eq(tripASecond.getPayload()));
        verify(outboxEventRepository).delete(tripB);
        verify(outboxEventRepository, never()).delete(tripAFirst);
        verify(outboxEventRepository, never()).delete(tripASecond);
    }

    @Test
    @DisplayName("Nothing pending: nothing is sent")
    void publishPending_emptyOutbox_sendsNothing() {
        when(outboxEventRepository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of());

        assertEquals(0, relay.publishPending());

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}

package com.example.tripservice.service;

import com.example.tripservice.entity.OutboxEvent;
import com.example.tripservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;

class OutboxEventPollerTest {

    @Test
    void brokerRejectsAsync_keepsOutboxAndDoesNotOvertakeSameTrip() {
        var repository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var first = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("trip-1")
            .payload("first")
            .createdAt(Instant.now())
            .build();
        var later = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("trip-1")
            .payload("later")
            .createdAt(Instant.now().plusSeconds(1))
            .build();
        when(repository.findAll()).thenReturn(List.of(first, later));
        when(kafka.send("trip-events", "trip-1", "first"))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        new OutboxEventPoller(repository, Optional.of(kafka)).pollOutbox();
        verify(repository, never()).delete(any());
        verify(kafka, never()).send("trip-events", "trip-1", "later");
    }

    @Test
    void brokerAcknowledges_deletesOnlyAcknowledgedEvent() {
        var repository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var event = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("trip-1")
            .payload("event")
            .createdAt(Instant.now())
            .build();
        when(repository.findAll()).thenReturn(List.of(event));
        when(kafka.send("trip-events", "trip-1", "event"))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        new OutboxEventPoller(repository, Optional.of(kafka)).pollOutbox();
        verify(repository).delete(event);
    }

}

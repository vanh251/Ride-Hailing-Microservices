package com.example.tripservice.integration;

import com.example.tripservice.entity.OutboxEvent;
import com.example.tripservice.repository.*;
import com.example.tripservice.service.OutboxEventPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "outbox.poll-interval-ms=3600000", "logging.level.org.apache.kafka=ERROR", "logging.level.kafka=ERROR" })
@EmbeddedKafka(partitions = 3, topics = { "trip-events", "matching-events", "payment-events" },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaRuntimeWiringTest {

    @MockitoBean
    TripRepository trips;

    @MockitoBean
    RideRequestRepository rides;

    @MockitoBean
    TripStatusHistoryRepository histories;

    @MockitoBean
    SagaLogRepository saga;

    @MockitoBean
    ProcessedEventRepository processed;

    @MockitoBean
    OutboxEventRepository outbox;

    @Autowired
    OutboxEventPoller poller;

    @Test
    void autoConfiguredKafkaTemplatePublishesBeforeDeletingOutbox() {
        var event = OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId(UUID.randomUUID().toString())
            .payload("{\"eventType\":\"TripCompleted\"}")
            .createdAt(Instant.now())
            .build();
        when(outbox.findAll()).thenReturn(List.of(event), List.of());
        poller.pollOutbox();
        verify(outbox, timeout(5000)).delete(event);
    }

}

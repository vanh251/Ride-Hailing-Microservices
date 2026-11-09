package com.example.tripservice.integration;

import com.example.tripservice.entity.OutboxEvent;
import com.example.tripservice.repository.OutboxEventRepository;
import com.example.tripservice.service.OutboxEventPoller;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class KafkaRelayIntegrationTest {

    @Test
    void outboxIsAcknowledgedAndDeliveredWithTripPartitionKey() {
        var broker = new EmbeddedKafkaKraftBroker(1, 3, "trip-events");
        DefaultKafkaProducerFactory<String, Object> factory = null;
        try {
            broker.afterPropertiesSet();
            factory = new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    broker.getBrokersAsString(), ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
            var kafka = new KafkaTemplate<String, Object>(factory);
            var repository = mock(OutboxEventRepository.class);
            UUID eventId = UUID.randomUUID(), tripId = UUID.randomUUID();
            String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TripCompleted\"}";
            var event = OutboxEvent.builder()
                .id(eventId)
                .aggregateId(tripId.toString())
                .payload(payload)
                .createdAt(Instant.now())
                .build();
            when(repository.findAll()).thenReturn(List.of(event));
            new OutboxEventPoller(repository, Optional.of(kafka)).pollOutbox();
            verify(repository).delete(event);
            try (var consumer = new KafkaConsumer<String, String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    broker.getBrokersAsString(), ConsumerConfig.GROUP_ID_CONFIG, "m2-test-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                    false, ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                assertEquals(3, consumer.partitionsFor("trip-events").size());
                consumer.subscribe(List.of("trip-events"));
                var received = new ArrayList<ConsumerRecord<String, String>>();
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (received.isEmpty() && System.nanoTime() < deadline)
                    consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                assertEquals(1, received.size());
                assertEquals(tripId.toString(), received.get(0).key());
                assertEquals(payload, received.get(0).value());
            }
            kafka.destroy();
        }
        finally {
            if (factory != null)
                factory.destroy();
            broker.destroy();
        }
    }

}

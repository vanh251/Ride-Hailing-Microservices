package com.example.driverservice.kafka.producer;

import com.example.driverservice.entity.OutboxEvent;
import com.example.driverservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the outbox of driver_db to driver-events (docs/events.md §3, §4.10, §4.11), keyed by tripId.
 * A row is deleted only after the broker acknowledged it. When a send fails the row stays for the next run, and the
 * later rows of the same trip wait too, so a trip's results are never published out of order. A resend publishes
 * the stored envelope unchanged, with the same eventId, so consumers deduplicate it.
 */
@Slf4j
@Component
public class DriverEventOutboxRelay {

    public static final String DRIVER_EVENTS_TOPIC = "driver-events";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long ackTimeoutMs;

    public DriverEventOutboxRelay(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  @Value("${driver.outbox.ack-timeout-ms:5000}") long ackTimeoutMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    /** Sends every pending event once, oldest first. Returns how many were published and removed. */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findAllByOrderByCreatedAtAscIdAsc();
        Set<String> blockedTrips = new HashSet<>();
        int published = 0;
        for (OutboxEvent event : pending) {
            if (blockedTrips.contains(event.getAggregateId())) {
                continue;
            }
            try {
                kafkaTemplate.send(DRIVER_EVENTS_TOPIC, event.getAggregateId(), event.getPayload())
                        .get(ackTimeoutMs, TimeUnit.MILLISECONDS);
                outboxEventRepository.delete(event);
                published++;
                log.info("Published outbox event: topic={}, eventType={}, eventId={}, tripId={}",
                        DRIVER_EVENTS_TOPIC, event.getEventType(), event.getId(), event.getAggregateId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted, eventId={} kept for the next run", event.getId());
                return published;
            } catch (Exception e) {
                blockedTrips.add(event.getAggregateId());
                log.warn("Outbox event not published, kept for retry: eventType={}, eventId={}, tripId={}, error={}",
                        event.getEventType(), event.getId(), event.getAggregateId(), e.toString());
            }
        }
        return published;
    }
}

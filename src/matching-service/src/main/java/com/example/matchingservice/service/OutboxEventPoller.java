package com.example.matchingservice.service;

import com.example.matchingservice.entity.OutboxEvent;
import com.example.matchingservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OutboxEventPoller {

    private final OutboxEventRepository repository;

    private final KafkaTemplate<String, Object> kafka;

    @Value("${outbox.ack-timeout-ms:5000}")
    private long ackTimeoutMs = 5000;

    public OutboxEventPoller(OutboxEventRepository repository, Optional<KafkaTemplate<String, Object>> kafka) {
        this.repository = repository;
        this.kafka = kafka.orElse(null);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}")
    @Transactional
    public void pollOutbox() {
        if (kafka == null)
            return;
        var events = new ArrayList<>(repository.findAll());
        events.sort(Comparator.comparing(OutboxEvent::getCreatedAt).thenComparing(OutboxEvent::getId));
        Set<String> blocked = new HashSet<>();
        for (var event : events) {
            if (blocked.contains(event.getAggregateId()))
                continue;
            try {
                kafka.send("matching-events", event.getAggregateId(), event.getPayload())
                    .get(ackTimeoutMs, TimeUnit.MILLISECONDS);
                repository.delete(event);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Outbox interrupted; event {} retained", event.getId());
                return;
            }
            catch (Exception ex) {
                blocked.add(event.getAggregateId());
                log.warn("Outbox event {} retained for retry", event.getId(), ex);
            }
        }
    }

}

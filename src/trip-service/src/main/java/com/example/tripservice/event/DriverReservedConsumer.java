package com.example.tripservice.event;

import com.example.tripservice.service.TripService;
import com.example.tripservice.entity.ProcessedEvent;
import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.exception.ValidationException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.*;
import static com.example.tripservice.event.EventValues.*;

@Component
@RequiredArgsConstructor
public class DriverReservedConsumer {

    private final TripService tripService;

    private final ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "matching-events", groupId = "${spring.kafka.consumer.group-id:trip-service-group}")
    @Transactional
    public void handleDriverReserved(ConsumerRecord<String, String> record) {
        EventEnvelope<Map<String, Object>> e;
        try {
            e = objectMapper.readValue(record.value(), new TypeReference<EventEnvelope<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            throw new ValidationException("Malformed matching event", ex);
        }
        if (e == null)
            throw new ValidationException("Event cannot be null");
        if (!"DriverReserved".equals(e.getEventType()))
            return;
        if (e.getEventId() != null && processedEventRepository.existsById(e.getEventId().toString()))
            return;
        validate(e);
        var p = e.getPayload();
        UUID tripId = uuid(p, "tripId"), driverId = uuid(p, "driverId");
        uuid(p, "passengerId");
        Instant reservedUntil = time(p, "reservedUntil");
        double rawVersion = number(p, "reservationVersion");
        if (rawVersion < 1 || rawVersion != Math.rint(rawVersion) || rawVersion > Integer.MAX_VALUE)
            throw new ValidationException("Invalid reservationVersion");
        if (!"Trip".equals(e.getAggregateType()) || !tripId.toString().equals(e.getAggregateId()))
            throw new ValidationException("Trip aggregate mismatch");
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", e.getCorrelationId());
        try {
            tripService.beginAssignment(tripId, driverId, (int) rawVersion, reservedUntil, e.getEventId());
            processedEventRepository.save(ProcessedEvent.builder()
                .eventId(e.getEventId().toString())
                .eventType(e.getEventType())
                .processedAt(Instant.now())
                .build());
        }
        finally {
            if (previous == null)
                MDC.remove("correlationId");
            else
                MDC.put("correlationId", previous);
        }
    }

}

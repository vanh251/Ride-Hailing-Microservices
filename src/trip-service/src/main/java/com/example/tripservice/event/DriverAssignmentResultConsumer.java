package com.example.tripservice.event;

import com.example.tripservice.entity.ProcessedEvent;
import com.example.tripservice.exception.ValidationException;
import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.service.TripService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static com.example.tripservice.event.EventValues.*;

@Component
@RequiredArgsConstructor
public class DriverAssignmentResultConsumer {
    private final TripService tripService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "driver-events", groupId = "${spring.kafka.consumer.group-id:trip-service-group}")
    @Transactional
    public void onDriverOutcome(ConsumerRecord<String, String> record) {
        EventEnvelope<Map<String, Object>> event;
        try {
            event = objectMapper.readValue(record.value(), new TypeReference<EventEnvelope<Map<String, Object>>>() {});
        }
        catch (Exception ex) {
            throw new ValidationException("Malformed Driver outcome", ex);
        }
        if (event == null)
            throw new ValidationException("Driver outcome is null");
        boolean confirmed = "DriverAssignmentConfirmed".equals(event.getEventType());
        if (!confirmed && !"DriverAssignmentRejected".equals(event.getEventType()))
            return;
        if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId().toString()))
            return;
        validate(event);
        if (!"Trip".equals(event.getAggregateType()) || !"driver-service".equals(event.getProducer()))
            throw new ValidationException("Invalid Driver outcome producer");
        var payload = event.getPayload();
        UUID tripId = uuid(payload, "tripId");
        UUID driverId = uuid(payload, "driverId");
        UUID command = uuid(payload, "driverAssignedEventId");
        double rawVersion = number(payload, "reservationVersion");
        if (rawVersion < 1 || rawVersion != Math.rint(rawVersion) || rawVersion > Integer.MAX_VALUE
                || !tripId.toString().equals(event.getAggregateId())
                || !command.toString().equals(event.getCausationId()))
            throw new ValidationException("Invalid Driver outcome identity");
        tripService.applyAssignmentOutcome(tripId, driverId, (int) rawVersion, command, confirmed);
        processedEventRepository.save(ProcessedEvent.builder().eventId(event.getEventId().toString())
            .eventType(event.getEventType()).processedAt(Instant.now()).build());
    }
}

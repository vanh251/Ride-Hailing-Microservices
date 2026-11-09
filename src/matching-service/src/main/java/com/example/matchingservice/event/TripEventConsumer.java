package com.example.matchingservice.event;

import com.example.matchingservice.service.MatchingService;
import com.example.matchingservice.exception.ValidationException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TripEventConsumer {

    private final MatchingService service;

    private final ObjectMapper mapper;

    @KafkaListener(topics = "trip-events", groupId = "${spring.kafka.consumer.group-id:matching-service-group}")
    public void onTripEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Map<String, Object>> event;
        try {
            event = mapper.readValue(record.value(), new TypeReference<EventEnvelope<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            throw new ValidationException("Malformed trip event", ex);
        }
        if (event == null)
            throw new ValidationException("Event cannot be null");
        if (!"RideRequested".equals(event.getEventType()) && !"TripCancelled".equals(event.getEventType()))
            return;
        String previous = MDC.get("correlationId");
        if (event.getCorrelationId() != null)
            MDC.put("correlationId", event.getCorrelationId());
        try {
            if ("RideRequested".equals(event.getEventType()))
                service.handleRideRequested(event);
            else
                service.handleTripCancelled(event);
        }
        finally {
            if (previous == null)
                MDC.remove("correlationId");
            else
                MDC.put("correlationId", previous);
        }
    }

}

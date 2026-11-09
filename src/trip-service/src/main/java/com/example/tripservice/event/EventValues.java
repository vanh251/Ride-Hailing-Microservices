package com.example.tripservice.event;

import com.example.tripservice.exception.ValidationException;
import java.time.Instant;
import java.util.*;

public final class EventValues {

    private EventValues() {
    }

    public static UUID uuid(Map<String, Object> map, String field) {
        try {
            String value = text(map, field);
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value))
                throw new IllegalArgumentException();
            return id;
        }
        catch (RuntimeException ex) {
            throw new ValidationException("Invalid UUID: " + field, ex);
        }
    }

    public static String text(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String s) || s.isBlank())
            throw new ValidationException("Missing/invalid " + field);
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof Map<?, ?> value))
            throw new ValidationException("Missing object " + field);
        return (Map<String, Object>) value;
    }

    public static double number(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new ValidationException("Missing/invalid number " + field);
        return n.doubleValue();
    }

    public static Instant time(Map<String, Object> map, String field) {
        try {
            return Instant.parse(text(map, field));
        }
        catch (RuntimeException ex) {
            throw new ValidationException("Invalid timestamp " + field, ex);
        }
    }

    public static void validate(EventEnvelope<?> e) {
        if (e.getEventId() == null || e.getPayload() == null || e.getOccurredAt() == null || e.getProducer() == null
                || e.getProducer().isBlank()
                || !"Trip".equals(e.getAggregateType()) && !"Payment".equals(e.getAggregateType())
                || !"1.0".equals(e.getSchemaVersion()))
            throw new ValidationException("Invalid event envelope");
        uuid(Map.of("id", Objects.toString(e.getAggregateId(), "")), "id");
        uuid(Map.of("id", Objects.toString(e.getCorrelationId(), "")), "id");
        if (e.getCausationId() != null)
            uuid(Map.of("id", e.getCausationId()), "id");
    }

}

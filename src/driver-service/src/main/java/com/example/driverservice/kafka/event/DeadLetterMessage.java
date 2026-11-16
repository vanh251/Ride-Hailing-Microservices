package com.example.driverservice.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

// DLQ message format (docs/events.md §6)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterMessage {
    private String originalTopic;
    private Integer originalPartition;
    private Long originalOffset;
    private JsonNode originalMessage;
    private String error;
    private Integer retryCount;
    private Instant firstFailedAt;
    private Instant lastFailedAt;
    private String correlationId;
}

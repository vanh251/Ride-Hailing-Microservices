package com.example.driverservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side deduplication (docs/database_schema.md, table processed_events of driver_db): one row per Kafka
 * event this service has handled, written in the same transaction as the driver status change.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    // The eventId of the incoming event, not a generated value
    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    public void prePersist() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}

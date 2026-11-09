package com.example.matchingservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "match_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchRequest implements org.springframework.data.domain.Persistable<UUID> {

    @Id
    @Column(name = "request_id", updatable = false, nullable = false)
    private UUID requestId;

    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public UUID getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    public void markPersisted() {
        newEntity = false;
    }

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "passenger_id", nullable = false)
    private UUID passengerId;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "pickup_lat", nullable = false)
    private Double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private Double pickupLng;

    @Column(name = "search_radius_km", nullable = false)
    @Builder.Default
    private Double searchRadiusKm = 5.0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MatchStatus status = MatchStatus.PENDING;

    @Column(name = "matched_driver_id")
    private UUID matchedDriverId;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 1;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.expiresAt == null) {
            this.expiresAt = now.plusSeconds(60);
        }
        if (this.searchRadiusKm == null) {
            this.searchRadiusKm = 5.0;
        }
        if (this.status == null) {
            this.status = MatchStatus.PENDING;
        }
        if (this.attemptCount == null) {
            this.attemptCount = 1;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

}

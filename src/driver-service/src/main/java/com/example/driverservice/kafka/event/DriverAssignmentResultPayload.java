package com.example.driverservice.kafka.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Payload of DriverAssignmentConfirmed and DriverAssignmentRejected (docs/events.md §4.10, §4.11).
 * reservationVersion and driverAssignedEventId repeat the DriverAssigned they answer, which is how trip-service
 * matches the result to its pending command. passengerId is copied from DriverAssigned for notification-service
 * and left out when DriverAssigned had none.
 */
public record DriverAssignmentResultPayload(
        UUID tripId,
        UUID driverId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID passengerId,
        int reservationVersion,
        UUID driverAssignedEventId) {
}

package com.example.driverservice.dto.response;

import com.example.driverservice.entity.DriverStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Driver reservation held for a trip until reservedUntil")
public class ReserveDriverResponse {

    @Schema(description = "Driver id (= the driver's user id)", example = "d0000000-0000-4000-8000-000000000001")
    private UUID driverId;

    @Schema(description = "Driver status after the reservation", example = "RESERVED")
    private DriverStatus status;

    @Schema(description = "Trip the driver is reserved for", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID reservedForTrip;

    @Schema(description = "End of the lease (UTC); the driver returns to AVAILABLE if not assigned by then", example = "2026-09-30T10:12:30Z")
    private Instant reservedUntil;

    @Schema(description = "Driver version after the reservation (expectedVersion + 1)", example = "6")
    private Integer version;
}

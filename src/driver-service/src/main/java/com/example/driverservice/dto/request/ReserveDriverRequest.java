package com.example.driverservice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reservation of a driver for a trip, sent by matching-service")
public class ReserveDriverRequest {

    @Schema(description = "Trip the driver is reserved for", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "tripId is required")
    private UUID tripId;

    @Schema(description = "Driver version read from GET /api/drivers/available; the reservation fails if it changed", example = "5")
    @NotNull(message = "expectedVersion is required")
    @Min(value = 0, message = "expectedVersion must be 0 or greater")
    private Integer expectedVersion;
}

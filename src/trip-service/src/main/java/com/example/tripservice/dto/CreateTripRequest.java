package com.example.tripservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request body to create a new ride request")
public class CreateTripRequest {

    @Schema(description = "Ignored; passenger identity is taken from the authenticated gateway header",
            deprecated = true)
    private UUID passengerId;

    @NotNull(message = "Pickup location is required")
    @Valid
    @Schema(description = "Pickup location with coordinates and address", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocationDto pickup;

    @NotNull(message = "Destination location is required")
    @Valid
    @Schema(description = "Destination location with coordinates and address",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocationDto destination;

    @Schema(description = "Ignored; estimated fare is obtained from Pricing Service", deprecated = true)
    private BigDecimal estimatedFare;

}

package com.example.pricingservice.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response containing estimated route metrics and upfront quote breakdown")
public class QuoteResponse {

    @Schema(description = "Estimated trip distance in kilometers", example = "2.5")
    private Double estimatedDistanceKm;

    @Schema(description = "Estimated trip duration in minutes", example = "12")
    private Integer estimatedDurationMin;

    @Schema(description = "Base fare amount", example = "15000")
    private BigDecimal baseFare;

    @Schema(description = "Calculated distance component of fare", example = "12500")
    private BigDecimal distanceFare;

    @Schema(description = "Calculated time component of fare", example = "6000")
    private BigDecimal timeFare;

    @Schema(description = "Estimated total fare", example = "33500")
    private BigDecimal totalFare;

    @Schema(description = "Currency code", example = "VND")
    private String currency;

    @Schema(description = "The driving route as [latitude, longitude] points for a map; null when the routing server "
            + "was unavailable and the distance is the straight-line estimate", example = "[[21.0242, 105.8576], [21.0293, 105.8355]]")
    private List<List<Double>> routePoints;
}

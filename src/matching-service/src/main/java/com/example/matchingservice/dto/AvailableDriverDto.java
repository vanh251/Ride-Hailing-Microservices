package com.example.matchingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailableDriverDto {

    private UUID id;

    private String fullName;

    private String status;

    private DriverLocation location;

    private Double distanceKm;

    private Integer version;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DriverLocation {

        private Double latitude;

        private Double longitude;

    }

}

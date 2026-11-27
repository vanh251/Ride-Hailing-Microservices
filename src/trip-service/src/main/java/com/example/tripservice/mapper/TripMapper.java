package com.example.tripservice.mapper;

import com.example.tripservice.dto.LocationDto;
import com.example.tripservice.dto.TripResponse;
import com.example.tripservice.entity.Trip;
import org.springframework.stereotype.Component;

@Component
public class TripMapper {

    public TripResponse toResponse(Trip trip) {
        if (trip == null) {
            return null;
        }

        LocationDto pickup = LocationDto.builder()
                .latitude(trip.getPickupLat())
                .longitude(trip.getPickupLng())
                .address(trip.getPickupAddress())
                .build();

        LocationDto destination = LocationDto.builder()
                .latitude(trip.getDropoffLat())
                .longitude(trip.getDropoffLng())
                .address(trip.getDropoffAddress())
                .build();

        return TripResponse.builder()
                .tripId(trip.getTripId())
                .status(trip.getStatus())
                .passengerId(trip.getPassengerId())
                .driverId(trip.getDriverId())
                .pickup(pickup)
                .destination(destination)
                .estimatedFare(trip.getEstimatedFare())
                .finalFare(trip.getFinalFare())
                .paymentProvider(trip.getPaymentProvider())
                .currency(trip.getCurrency())
                .correlationId(trip.getCorrelationId())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .acceptedAt(trip.getAcceptedAt())
                .startedAt(trip.getStartedAt())
                .completedAt(trip.getCompletedAt())
                .cancelledAt(trip.getCancelledAt())
                .build();
    }
}

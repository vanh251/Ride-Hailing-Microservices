package com.example.pricingservice.service.impl;

import com.example.pricingservice.client.RoutingClient;
import com.example.pricingservice.dto.request.FinalFareRequest;
import com.example.pricingservice.dto.request.LocationDto;
import com.example.pricingservice.dto.request.QuoteRequest;
import com.example.pricingservice.dto.response.FinalFareResponse;
import com.example.pricingservice.dto.response.QuoteResponse;
import com.example.pricingservice.service.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Fare formula (docs/api.md §6): total = baseFare + distanceKm * distanceRate + durationMin * timeRate.
 * Money is computed with BigDecimal and rounded to whole VND. The quote's distance is the driving distance along
 * the streets; the duration assumes average city traffic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    public static final BigDecimal BASE_FARE = new BigDecimal("15000");
    public static final BigDecimal DISTANCE_RATE_PER_KM = new BigDecimal("5000");
    public static final BigDecimal TIME_RATE_PER_MIN = new BigDecimal("500");
    public static final String CURRENCY = "VND";
    private static final double AVERAGE_CITY_SPEED_KMH = 20.0;
    // Fallback when the routing server cannot answer: straight-line distance times this factor approximates the streets
    private static final double ROUTE_DETOUR_FACTOR = 1.25;
    private static final double MIN_DISTANCE_KM = 0.5;
    private static final int EARTH_RADIUS_KM = 6371;

    private final RoutingClient routingClient;

    @Override
    public QuoteResponse calculateQuote(QuoteRequest request) {
        log.info("Calculating quote: pickup=({}, {}), destination=({}, {})",
                request.getPickup().getLatitude(), request.getPickup().getLongitude(),
                request.getDestination().getLatitude(), request.getDestination().getLongitude());

        Optional<RoutingClient.Route> road = routingClient.drivingRoute(request.getPickup(), request.getDestination());
        double distanceKm = road.map(route -> Math.max(MIN_DISTANCE_KM, roundOneDecimal(route.distanceKm())))
                .orElseGet(() -> estimateRouteDistanceKm(request.getPickup(), request.getDestination()));
        int durationMin = estimateDurationMinutes(distanceKm);

        BigDecimal distanceFare = distanceFare(distanceKm);
        BigDecimal timeFare = timeFare(durationMin);
        BigDecimal totalFare = BASE_FARE.add(distanceFare).add(timeFare);

        log.info("Quote calculated: distance={} km ({}), duration={} min, totalFare={} {}",
                distanceKm, road.isPresent() ? "road" : "straight-line estimate", durationMin, totalFare, CURRENCY);

        return QuoteResponse.builder()
                .estimatedDistanceKm(distanceKm)
                .estimatedDurationMin(durationMin)
                .baseFare(BASE_FARE)
                .distanceFare(distanceFare)
                .timeFare(timeFare)
                .totalFare(totalFare)
                .currency(CURRENCY)
                .routePoints(road.map(RoutingClient.Route::points).orElse(null))
                .build();
    }

    @Override
    public FinalFareResponse calculateFinalFare(FinalFareRequest request) {
        double distanceKm = roundOneDecimal(request.getActualDistanceKm());
        int durationMin = request.getActualDurationMin();

        BigDecimal distanceFare = distanceFare(distanceKm);
        BigDecimal timeFare = timeFare(durationMin);
        BigDecimal totalFare = BASE_FARE.add(distanceFare).add(timeFare);

        log.info("Final fare calculated: tripId={}, distance={} km, duration={} min, totalFare={} {}",
                request.getTripId(), distanceKm, durationMin, totalFare, CURRENCY);

        return FinalFareResponse.builder()
                .tripId(request.getTripId())
                .baseFare(BASE_FARE)
                .distanceFare(distanceFare)
                .timeFare(timeFare)
                .totalFare(totalFare)
                .currency(CURRENCY)
                .build();
    }

    private static BigDecimal distanceFare(double distanceKm) {
        return BigDecimal.valueOf(distanceKm).multiply(DISTANCE_RATE_PER_KM).setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal timeFare(int durationMin) {
        return BigDecimal.valueOf(durationMin).multiply(TIME_RATE_PER_MIN).setScale(0, RoundingMode.HALF_UP);
    }

    private double estimateRouteDistanceKm(LocationDto from, LocationDto to) {
        double lat1 = Math.toRadians(from.getLatitude());
        double lon1 = Math.toRadians(from.getLongitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double lon2 = Math.toRadians(to.getLongitude());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double straightLineKm = EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return Math.max(MIN_DISTANCE_KM, roundOneDecimal(straightLineKm * ROUTE_DETOUR_FACTOR));
    }

    private int estimateDurationMinutes(double distanceKm) {
        int minutes = (int) Math.round(distanceKm / AVERAGE_CITY_SPEED_KMH * 60);
        return Math.max(1, minutes);
    }

    private static double roundOneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}

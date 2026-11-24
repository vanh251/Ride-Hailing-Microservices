package com.example.pricingservice;

import com.example.pricingservice.client.RoutingClient;
import com.example.pricingservice.dto.request.FinalFareRequest;
import com.example.pricingservice.dto.request.LocationDto;
import com.example.pricingservice.dto.request.QuoteRequest;
import com.example.pricingservice.dto.response.FinalFareResponse;
import com.example.pricingservice.dto.response.QuoteResponse;
import com.example.pricingservice.service.PricingService;
import com.example.pricingservice.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingServiceTest {

    private PricingService pricingService;
    private RoutingClient routingClient;

    // Without a road route (routing server down) the quote uses the straight-line estimate, as most tests below check
    @BeforeEach
    void setUp() {
        routingClient = mock(RoutingClient.class);
        when(routingClient.drivingRoute(any(), any())).thenReturn(Optional.empty());
        pricingService = new PricingServiceImpl(routingClient);
    }

    @Test
    @DisplayName("calculateQuote prices the driving distance along the streets and returns the road for the map")
    void calculateQuote_roadRoute_usesDrivingDistance() {
        List<List<Double>> road = List.of(List.of(21.0242, 105.8576), List.of(21.0260, 105.8450), List.of(21.0293, 105.8355));
        when(routingClient.drivingRoute(any(), any())).thenReturn(Optional.of(new RoutingClient.Route(2.98, road)));

        QuoteResponse response = pricingService.calculateQuote(QuoteRequest.builder()
                .pickup(new LocationDto(21.0242, 105.8576, "Nha hat Lon"))
                .destination(new LocationDto(21.0293, 105.8355, "Van Mieu"))
                .build());

        // 2.98 km -> 3.0 km, at 20 km/h -> 9 min: 15000 + 3.0 * 5000 + 9 * 500
        assertEquals(3.0, response.getEstimatedDistanceKm());
        assertEquals(9, response.getEstimatedDurationMin());
        assertMoney("34500", response.getTotalFare());
        assertEquals(road, response.getRoutePoints());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("calculateQuote estimates route distance (Haversine x 1.25) and duration from coordinates")
    void calculateQuote_coordinates_returnsEstimatedFare() {
        QuoteRequest request = QuoteRequest.builder()
                .pickup(new LocationDto(21.0285, 105.8542, "Hoan Kiem, Hanoi"))
                .destination(new LocationDto(21.0188, 105.8520, "Dong Da, Hanoi"))
                .build();

        QuoteResponse response = pricingService.calculateQuote(request);

        // Straight line ~1.10 km, route ~1.4 km, 1.4 km at 20 km/h ~4 min
        assertEquals(1.4, response.getEstimatedDistanceKm());
        assertEquals(4, response.getEstimatedDurationMin());
        assertMoney("15000", response.getBaseFare());
        assertMoney("7000", response.getDistanceFare());   // 1.4 * 5000
        assertMoney("2000", response.getTimeFare());       // 4 * 500
        assertMoney("24000", response.getTotalFare());
        assertEquals("VND", response.getCurrency());
        assertNull(response.getRoutePoints());
    }

    @Test
    @DisplayName("calculateQuote for the same pickup and destination applies the 0.5 km minimum")
    void calculateQuote_samePoint_appliesMinimumDistance() {
        LocationDto point = new LocationDto(21.0285, 105.8542, "Hoan Kiem, Hanoi");
        QuoteResponse response = pricingService.calculateQuote(QuoteRequest.builder()
                .pickup(point)
                .destination(point)
                .build());

        assertEquals(0.5, response.getEstimatedDistanceKm());
        assertEquals(2, response.getEstimatedDurationMin());
        assertMoney("18500", response.getTotalFare());    // 15000 + 2500 + 1000
    }

    @Test
    @DisplayName("calculateFinalFare returns the docs/api.md example fare: 2.8 km at 20 km/h = 8 min")
    void calculateFinalFare_validRequest_returnsAccurateFinalFare() {
        FinalFareRequest request = FinalFareRequest.builder()
                .tripId("trip-001")
                .actualDistanceKm(2.8)
                .build();

        FinalFareResponse response = pricingService.calculateFinalFare(request);

        assertEquals("trip-001", response.getTripId());
        assertMoney("15000", response.getBaseFare());
        assertMoney("14000", response.getDistanceFare()); // 2.8 * 5000
        assertMoney("4000", response.getTimeFare());      // 8 * 500
        assertMoney("33000", response.getTotalFare());
        assertEquals("VND", response.getCurrency());
    }

    @Test
    @DisplayName("calculateFinalFare charges exactly the quoted price, whatever the distance")
    void calculateFinalFare_quotedDistance_equalsQuote() {
        for (double[] trip : new double[][]{{21.0242, 105.8576, 21.0293, 105.8355}, {21.0285, 105.8542, 21.0598, 105.8195}, {21.0, 105.0, 21.0, 105.0}}) {
            QuoteResponse quote = pricingService.calculateQuote(QuoteRequest.builder()
                    .pickup(new LocationDto(trip[0], trip[1], "A")).destination(new LocationDto(trip[2], trip[3], "B")).build());

            FinalFareResponse fare = pricingService.calculateFinalFare(FinalFareRequest.builder()
                    .tripId("trip").actualDistanceKm(quote.getEstimatedDistanceKm()).build());

            assertMoney(quote.getTotalFare().toPlainString(), fare.getTotalFare());
        }
    }

    @Test
    @DisplayName("calculateFinalFare keeps whole VND without floating point drift")
    void calculateFinalFare_fractionalDistance_roundsToWholeVnd() {
        FinalFareResponse response = pricingService.calculateFinalFare(FinalFareRequest.builder()
                .tripId("trip-002")
                .actualDistanceKm(0.3)
                .build());

        assertEquals(new BigDecimal("1500"), response.getDistanceFare());
        assertEquals(new BigDecimal("17000"), response.getTotalFare());
    }
}

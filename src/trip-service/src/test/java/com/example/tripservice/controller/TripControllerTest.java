package com.example.tripservice.controller;

import com.example.tripservice.dto.CancelTripRequest;
import com.example.tripservice.dto.CreateTripRequest;
import com.example.tripservice.dto.LocationDto;
import com.example.tripservice.dto.TripResponse;
import com.example.tripservice.entity.TripStatus;
import com.example.tripservice.exception.GlobalExceptionHandler;
import com.example.tripservice.exception.InvalidTripStateException;
import com.example.tripservice.exception.TripNotFoundException;
import com.example.tripservice.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TripControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TripService tripService;

    @InjectMocks
    private TripController tripController;

    private UUID tripId;

    private UUID passengerId;

    private UUID driverId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(tripController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        tripId = UUID.randomUUID();
        passengerId = UUID.randomUUID();
        driverId = UUID.randomUUID();
    }

    @Test
    @DisplayName("POST /api/trips - Success 201")
    void createTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.SEARCHING)
            .passengerId(passengerId)
            .pickup(LocationDto.builder().latitude(21.0285).longitude(105.8542).address("Hanoi").build())
            .destination(LocationDto.builder().latitude(21.0188).longitude(105.8520).address("Hanoi").build())
            .estimatedFare(BigDecimal.valueOf(45000))
            .correlationId("corr-001")
            .createdAt(Instant.now())
            .build();

        when(tripService.createTrip(any(CreateTripRequest.class), any(), any())).thenReturn(response);

        String jsonPayload = """
                {
                  "passengerId": "%s",
                  "pickup": {
                    "latitude": 21.0285,
                    "longitude": 105.8542,
                    "address": "Hanoi"
                  },
                  "destination": {
                    "latitude": 21.0188,
                    "longitude": 105.8520,
                    "address": "Hanoi"
                  },
                  "estimatedFare": 45000
                }
                """.formatted(passengerId);

        mockMvc
            .perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-ID", "corr-001")
                .content(jsonPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.tripId").value(tripId.toString()))
            .andExpect(jsonPath("$.status").value("SEARCHING"))
            .andExpect(jsonPath("$.passengerId").value(passengerId.toString()));
    }

    @Test
    @DisplayName("GET /api/trips/{id} - Success 200")
    void getTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.ASSIGNED)
            .passengerId(passengerId)
            .driverId(driverId)
            .correlationId("corr-001")
            .build();

        when(tripService.getTrip(tripId)).thenReturn(response);

        mockMvc.perform(get("/api/trips/{id}", tripId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tripId").value(tripId.toString()))
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.driverId").value(driverId.toString()));
    }

    @Test
    @DisplayName("GET /api/trips/{id} - Not Found 404")
    void getTrip_notFound() throws Exception {
        when(tripService.getTrip(tripId)).thenThrow(new TripNotFoundException(tripId));

        mockMvc.perform(get("/api/trips/{id}", tripId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /api/trips/{id}/accept - Success 200")
    void acceptTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.ASSIGNED)
            .driverId(driverId)
            .build();

        when(tripService.acceptTrip(eq(tripId), any())).thenReturn(response);

        mockMvc.perform(post("/api/trips/{id}/accept", tripId).header("X-User-Id", driverId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.driverId").value(driverId.toString()));
    }

    @Test
    @DisplayName("POST /api/trips/{id}/start - Success 200")
    void startTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.IN_PROGRESS)
            .startedAt(Instant.now())
            .build();

        when(tripService.startTrip(tripId, driverId)).thenReturn(response);

        mockMvc.perform(post("/api/trips/{id}/start", tripId).header("X-User-Id", driverId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    @DisplayName("POST /api/trips/{id}/complete - Success 200")
    void completeTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.COMPLETED)
            .finalFare(BigDecimal.valueOf(47500))
            .completedAt(Instant.now())
            .build();

        when(tripService.completeTrip(tripId, driverId)).thenReturn(response);

        mockMvc.perform(post("/api/trips/{id}/complete", tripId).header("X-User-Id", driverId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.finalFare").value(47500));
    }

    @Test
    @DisplayName("POST /api/trips/{id}/cancel - Success 200")
    void cancelTrip_success() throws Exception {
        TripResponse response = TripResponse.builder()
            .tripId(tripId)
            .status(TripStatus.CANCELLED)
            .cancelledAt(Instant.now())
            .build();

        when(tripService.cancelTrip(eq(tripId), any(), eq(passengerId))).thenReturn(response);

        String jsonPayload = """
                {
                  "reason": "Change plans"
                }
                """;

        mockMvc
            .perform(post("/api/trips/{id}/cancel", tripId).header("X-User-Id", passengerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledAt").exists());
    }

    @Test
    @DisplayName("POST /api/trips/{id}/start - Conflict 409 on invalid state")
    void startTrip_invalidState_conflict() throws Exception {
        when(tripService.startTrip(tripId, driverId))
            .thenThrow(new InvalidTripStateException(TripStatus.SEARCHING, TripStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/trips/{id}/start", tripId).header("X-User-Id", driverId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

}

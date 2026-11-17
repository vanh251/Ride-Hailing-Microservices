package com.example.driverservice.controller;

import com.example.driverservice.dto.response.AvailableDriverResponse;
import com.example.driverservice.dto.response.DriverDetailResponse;
import com.example.driverservice.dto.response.DriverLocationResponse;
import com.example.driverservice.dto.response.VehicleResponse;
import com.example.driverservice.entity.VehicleType;
import com.example.driverservice.dto.response.DriverStatusResponse;
import com.example.driverservice.dto.response.LocationResponse;
import com.example.driverservice.dto.response.NearbyDriverResponse;
import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.exception.ConflictException;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.exception.BusinessException;
import com.example.driverservice.exception.GlobalExceptionHandler;
import com.example.driverservice.service.DriverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DriverControllerTest {

    private static final UUID DRIVER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private MockMvc mockMvc;

    @Mock
    private DriverService driverService;

    @InjectMocks
    private DriverController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        // What JwtAuthenticationFilter puts in the context for a DRIVER token
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                DRIVER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/drivers/available returns the fields matching-service reads (id, fullName, status, location, distanceKm, version)")
    void getAvailableDrivers_withPoint_returnsMatchingFields() throws Exception {
        when(driverService.findAvailableDrivers(21.0285, 105.8542, 3.0, 0, 20, false)).thenReturn(List.of(AvailableDriverResponse.builder()
                .id(DRIVER_ID)
                .fullName("Tran Van Binh")
                .status(DriverStatus.AVAILABLE)
                .location(LocationResponse.builder().latitude(21.025).longitude(105.857)
                        .updatedAt(Instant.parse("2026-10-09T10:00:00Z")).build())
                .distanceKm(0.49)
                .version(3)
                .build()));

        mockMvc.perform(get("/api/drivers/available")
                        .param("latitude", "21.0285").param("longitude", "105.8542").param("radiusKm", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$[0].fullName").value("Tran Van Binh"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].location.latitude").value(21.025))
                .andExpect(jsonPath("$[0].location.longitude").value(105.857))
                .andExpect(jsonPath("$[0].distanceKm").value(0.49))
                .andExpect(jsonPath("$[0].version").value(3));
    }

    @Test
    @DisplayName("GET /api/drivers/available uses the default 5 km radius and limit 20")
    void getAvailableDrivers_pointOnly_usesDefaults() throws Exception {
        when(driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, 0, 20, false)).thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/available").param("latitude", "21.0285").param("longitude", "105.8542"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(driverService).findAvailableDrivers(21.0285, 105.8542, 5.0, 0, 20, false);
    }

    @ParameterizedTest(name = "only {0}")
    @CsvSource({"latitude, 21.0", "longitude, 105.8"})
    @DisplayName("GET /api/drivers/available without both coordinates returns 400 VALIDATION_ERROR")
    void getAvailableDrivers_missingCoordinate_returns400(String name, String value) throws Exception {
        mockMvc.perform(get("/api/drivers/available").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"latitude, 91", "latitude, -90.5", "longitude, 180.1", "radiusKm, 0", "radiusKm, -1", "radiusKm, 50.5",
            "limit, 0", "limit, 51"})
    @DisplayName("GET /api/drivers/available with an out-of-range parameter returns 400 VALIDATION_ERROR")
    void getAvailableDrivers_outOfRange_returns400(String name, String value) throws Exception {
        var request = get("/api/drivers/available").param(name, value);
        if (!name.equals("latitude")) request.param("latitude", "21.0");
        if (!name.equals("longitude")) request.param("longitude", "105.8");

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(driverService, never()).findAvailableDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("GET /api/drivers/available with a non-numeric latitude returns 400 INVALID_PARAMETER")
    void getAvailableDrivers_nonNumeric_returns400() throws Exception {
        mockMvc.perform(get("/api/drivers/available").param("latitude", "abc").param("longitude", "105.8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/status passes the token's user id to the service and returns the new status")
    void updateStatus_validRequest_returns200() throws Exception {
        when(driverService.updateStatus(DRIVER_ID, DriverStatus.AVAILABLE, DRIVER_ID)).thenReturn(DriverStatusResponse.builder()
                .id(DRIVER_ID).status(DriverStatus.AVAILABLE).updatedAt(Instant.parse("2026-10-09T10:10:00Z")).build());

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/status without status returns 400 VALIDATION_ERROR")
    void updateStatus_missingStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/status with an unknown status value returns 400 MALFORMED_REQUEST")
    void updateStatus_unknownStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SLEEPING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(driverService);
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/status with a forbidden transition returns 422")
    void updateStatus_invalidTransition_returns422() throws Exception {
        when(driverService.updateStatus(DRIVER_ID, DriverStatus.BUSY, DRIVER_ID)).thenThrow(new BusinessException(
                "Status cannot be changed from AVAILABLE to BUSY", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_STATUS_TRANSITION"));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BUSY\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/status that loses a concurrent update returns 409 CONCURRENT_UPDATE")
    void updateStatus_optimisticLockFailure_returns409() throws Exception {
        when(driverService.updateStatus(DRIVER_ID, DriverStatus.OFFLINE, DRIVER_ID))
                .thenThrow(new ObjectOptimisticLockingFailureException("Driver", DRIVER_ID));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"OFFLINE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    @DisplayName("PATCH /api/drivers/{id}/location returns the stored position")
    void updateLocation_validRequest_returns200() throws Exception {
        when(driverService.updateLocation(DRIVER_ID, 21.03, 105.856, DRIVER_ID)).thenReturn(DriverLocationResponse.builder()
                .id(DRIVER_ID)
                .location(LocationResponse.builder().latitude(21.03).longitude(105.856)
                        .updatedAt(Instant.parse("2026-10-09T10:11:00Z")).build())
                .build());

        mockMvc.perform(patch("/api/drivers/{id}/location", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 21.03, \"longitude\": 105.856}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$.location.latitude").value(21.03))
                .andExpect(jsonPath("$.location.longitude").value(105.856))
                .andExpect(jsonPath("$.location.updatedAt").exists());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing latitude | {\"longitude\": 105.8}",
            "missing longitude | {\"latitude\": 21.0}",
            "latitude 91 | {\"latitude\": 91, \"longitude\": 105.8}",
            "longitude -181 | {\"latitude\": 21.0, \"longitude\": -181}"
    })
    @DisplayName("PATCH /api/drivers/{id}/location with missing or out-of-range coordinates returns 400 VALIDATION_ERROR")
    void updateLocation_invalidBody_returns400(String caseName, String body) throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/location", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    @Test
    @DisplayName("GET /api/drivers/{id} returns profile, vehicle and location")
    void getDriver_existingDriver_returns200() throws Exception {
        when(driverService.getDriver(DRIVER_ID, DRIVER_ID)).thenReturn(DriverDetailResponse.builder()
                .id(DRIVER_ID)
                .fullName("Tran Van Binh")
                .status(DriverStatus.AVAILABLE)
                .vehicle(VehicleResponse.builder().licensePlate("30A-10001").make("Toyota").model("Vios")
                        .color("White").year(2021).vehicleType(VehicleType.SEDAN).build())
                .location(LocationResponse.builder().latitude(21.025).longitude(105.857).build())
                .build());

        mockMvc.perform(get("/api/drivers/{id}", DRIVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$.fullName").value("Tran Van Binh"))
                .andExpect(jsonPath("$.vehicle.licensePlate").value("30A-10001"))
                .andExpect(jsonPath("$.vehicle.model").value("Vios"))
                .andExpect(jsonPath("$.location.latitude").value(21.025));
    }

    @Test
    @DisplayName("GET /api/drivers/{id} for an unknown driver returns 404 DRIVER_NOT_FOUND")
    void getDriver_unknown_returns404() throws Exception {
        when(driverService.getDriver(DRIVER_ID, DRIVER_ID))
                .thenThrow(new com.example.driverservice.exception.ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND"));

        mockMvc.perform(get("/api/drivers/{id}", DRIVER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/drivers/{id} with an id that is not a UUID returns 400 INVALID_PARAMETER")
    void getDriver_invalidId_returns400() throws Exception {
        mockMvc.perform(get("/api/drivers/{id}", "d001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("POST /api/drivers/{id}/reserve returns 200 with the fields matching-service reads")
    void reserveDriver_validRequest_returns200() throws Exception {
        UUID tripId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(driverService.reserveDriver(DRIVER_ID, tripId, 5)).thenReturn(ReserveDriverResponse.builder()
                .driverId(DRIVER_ID)
                .status(DriverStatus.RESERVED)
                .reservedForTrip(tripId)
                .reservedUntil(Instant.parse("2026-09-30T10:12:30Z"))
                .version(6)
                .build());

        mockMvc.perform(post("/api/drivers/{id}/reserve", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\": \"" + tripId + "\", \"expectedVersion\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservedForTrip").value(tripId.toString()))
                .andExpect(jsonPath("$.reservedUntil").value("2026-09-30T10:12:30Z"))
                .andExpect(jsonPath("$.version").value(6));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing tripId          | {\"expectedVersion\": 5}",
            "missing expectedVersion | {\"tripId\": \"550e8400-e29b-41d4-a716-446655440000\"}",
            "negative version        | {\"tripId\": \"550e8400-e29b-41d4-a716-446655440000\", \"expectedVersion\": -1}"
    })
    @DisplayName("POST /api/drivers/{id}/reserve with an invalid body returns 400 VALIDATION_ERROR")
    void reserveDriver_invalidBody_returns400(String caseName, String body) throws Exception {
        mockMvc.perform(post("/api/drivers/{id}/reserve", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    @Test
    @DisplayName("POST /api/drivers/{id}/reserve with a tripId that is not a UUID returns 400 MALFORMED_REQUEST")
    void reserveDriver_tripIdNotUuid_returns400() throws Exception {
        mockMvc.perform(post("/api/drivers/{id}/reserve", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\": \"t1\", \"expectedVersion\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/drivers/{id}/reserve returns 409 DRIVER_ALREADY_RESERVED when the driver was taken")
    void reserveDriver_driverTaken_returns409() throws Exception {
        when(driverService.reserveDriver(any(), any(), anyInt()))
                .thenThrow(new ConflictException("Driver is not available or was changed by another request", "DRIVER_ALREADY_RESERVED"));

        mockMvc.perform(post("/api/drivers/{id}/reserve", DRIVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\": \"550e8400-e29b-41d4-a716-446655440000\", \"expectedVersion\": 5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_ALREADY_RESERVED"));
    }

    @Test
    @DisplayName("DELETE /api/drivers/{id}/reserve returns 204 without a body")
    void releaseReservation_validRequest_returns204() throws Exception {
        mockMvc.perform(delete("/api/drivers/{id}/reserve", DRIVER_ID).param("tripId", TRIP_ID.toString()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(driverService).releaseReservation(DRIVER_ID, TRIP_ID);
    }

    @Test
    @DisplayName("DELETE /api/drivers/{id}/reserve for an unknown driver returns 404 DRIVER_NOT_FOUND")
    void releaseReservation_unknownDriver_returns404() throws Exception {
        doThrow(new com.example.driverservice.exception.ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND"))
                .when(driverService).releaseReservation(DRIVER_ID, TRIP_ID);

        mockMvc.perform(delete("/api/drivers/{id}/reserve", DRIVER_ID).param("tripId", TRIP_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/drivers/nearby uses the default radius 5 and limit 10 and returns id, fullName, distanceKm, location")
    void getNearbyDrivers_defaults_returns200() throws Exception {
        when(driverService.findNearbyDrivers(21.0285, 105.8542, 5.0, 0, 10, false)).thenReturn(List.of(NearbyDriverResponse.builder()
                .id(DRIVER_ID).fullName("Tran Van Binh").distanceKm(0.49)
                .location(LocationResponse.builder().latitude(21.025).longitude(105.857).build())
                .build()));

        mockMvc.perform(get("/api/drivers/nearby").param("latitude", "21.0285").param("longitude", "105.8542"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$[0].fullName").value("Tran Van Binh"))
                .andExpect(jsonPath("$[0].distanceKm").value(0.49))
                .andExpect(jsonPath("$[0].location.latitude").value(21.025));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing latitude | longitude=105.8",
            "missing longitude | latitude=21.0",
            "limit 0 | latitude=21.0&longitude=105.8&limit=0",
            "limit 51 | latitude=21.0&longitude=105.8&limit=51",
            "radius 60 | latitude=21.0&longitude=105.8&radiusKm=60"
    })
    @DisplayName("GET /api/drivers/nearby with a missing or out-of-range parameter returns 400 VALIDATION_ERROR")
    void getNearbyDrivers_invalidParams_returns400(String caseName, String query) throws Exception {
        mockMvc.perform(get("/api/drivers/nearby?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    // ---- pagination and sort of /available and /nearby (docs/api.md §8) ----

    @ParameterizedTest(name = "{0} -> page {1}, size {2}, farthest first {3}")
    @CsvSource(delimiter = '|', value = {
            "page=0&size=10&sort=distanceKm,asc | 0 | 10 | false",
            "page=2&size=5                      | 2 | 5  | false",
            "sort=distanceKm,desc               | 0 | 20 | true",
            "sort=distanceKm                    | 0 | 20 | false",
            "sort=DISTANCEKM,DESC               | 0 | 20 | true",
            "limit=7                            | 0 | 7  | false",
            "page=1&limit=7                     | 1 | 7  | false",
            "size=3&limit=7                     | 0 | 3  | false",
            "size=50                            | 0 | 50 | false"
    })
    @DisplayName("GET /api/drivers/available reads page, size and sort; limit still sets the page size when size is absent")
    void getAvailableDrivers_pageSizeSort_passedToService(String query, int page, int size, boolean farthestFirst) throws Exception {
        when(driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, page, size, farthestFirst)).thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/available?latitude=21.0285&longitude=105.8542&" + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(driverService).findAvailableDrivers(21.0285, 105.8542, 5.0, page, size, farthestFirst);
    }

    @ParameterizedTest(name = "{0} -> page {1}, size {2}, farthest first {3}")
    @CsvSource(delimiter = '|', value = {
            "page=1&size=5&sort=distanceKm,desc | 1 | 5  | true",
            "page=3                             | 3 | 10 | false",
            "size=4&limit=9                     | 0 | 4  | false"
    })
    @DisplayName("GET /api/drivers/nearby reads page, size and sort the same way, with its own default size 10")
    void getNearbyDrivers_pageSizeSort_passedToService(String query, int page, int size, boolean farthestFirst) throws Exception {
        when(driverService.findNearbyDrivers(21.0285, 105.8542, 5.0, page, size, farthestFirst)).thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/nearby?latitude=21.0285&longitude=105.8542&" + query))
                .andExpect(status().isOk());

        verify(driverService).findNearbyDrivers(21.0285, 105.8542, 5.0, page, size, farthestFirst);
    }

    @ParameterizedTest(name = "{0}?{1}")
    @CsvSource(delimiter = '|', value = {
            "available | page=-1",
            "available | size=0",
            "available | size=51",
            "available | sort=fullName,asc",
            "available | sort=distanceKm,up",
            "available | sort=distanceKm,asc,desc",
            "nearby    | page=-1",
            "nearby    | size=51",
            "nearby    | sort=version,desc"
    })
    @DisplayName("An out-of-range page or size, or a sort other than distanceKm asc/desc, returns 400 VALIDATION_ERROR")
    void searchDrivers_invalidPageSizeOrSort_returns400(String endpoint, String query) throws Exception {
        mockMvc.perform(get("/api/drivers/" + endpoint + "?latitude=21.0285&longitude=105.8542&" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(driverService);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"page, abc", "size, ten"})
    @DisplayName("A non-numeric page or size returns 400 INVALID_PARAMETER")
    void getAvailableDrivers_nonNumericPageOrSize_returns400(String name, String value) throws Exception {
        mockMvc.perform(get("/api/drivers/available").param("latitude", "21.0285").param("longitude", "105.8542")
                        .param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(driverService);
    }
}

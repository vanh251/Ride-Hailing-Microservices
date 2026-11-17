package com.example.driverservice;

import com.example.driverservice.config.InternalApiKeyInterceptor;
import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import com.example.driverservice.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the driver APIs through the real Spring Security chain and JwtAuthenticationFilter with tokens shaped like
 * identity-service's; only the repositories are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriverSecurityTest {

    private static final UUID DRIVER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverRepository driverRepository;

    @MockitoBean
    private VehicleRepository vehicleRepository;

    @MockitoBean
    private ProcessedEventRepository processedEventRepository;

    private static String statusBody(String status) {
        return "{\"status\": \"" + status + "\"}";
    }

    // POST carries the reserve body from docs/api.md; DELETE carries tripId in the query
    private static MockHttpServletRequestBuilder reserveRequest(String method) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), "/api/drivers/{id}/reserve", DRIVER_ID);
        return "POST".equals(method)
                ? builder.contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\": \"" + UUID.randomUUID() + "\", \"expectedVersion\": 3}")
                : builder.param("tripId", TRIP_ID.toString());
    }

    @Test
    @DisplayName("GET /api/drivers/available is reachable without a token (matching-service calls it without one)")
    void getAvailable_withoutToken_returns200() throws Exception {
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/available").param("latitude", "21.0285").param("longitude", "105.8542"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("PATCH status with the driver's own DRIVER token returns 200")
    void updateStatus_ownDriverToken_returns200() throws Exception {
        Driver driver = Driver.builder().driverId(DRIVER_ID).fullName("Tran Van Binh").status(DriverStatus.OFFLINE)
                .version(1).updatedAt(Instant.now()).build();
        when(driverRepository.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("PATCH status without a token returns 401 MISSING_TOKEN")
    void updateStatus_withoutToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header("X-Correlation-ID", "corr-401")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("MISSING_TOKEN"))
                .andExpect(jsonPath("$.correlationId").value("corr-401"));

        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PATCH status with an expired token returns 401 TOKEN_EXPIRED")
    void updateStatus_expiredToken_returns401() throws Exception {
        String expired = TestTokens.token(TestTokens.TEST_SECRET, DRIVER_ID.toString(), "DRIVER", Instant.now().minusSeconds(5));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(expired))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("PATCH status with a token signed by another key returns 401 INVALID_TOKEN")
    void updateStatus_foreignToken_returns401() throws Exception {
        String foreign = TestTokens.token(TestTokens.OTHER_SECRET, DRIVER_ID.toString(), "DRIVER", Instant.now().plusSeconds(60));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(foreign))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("PATCH status with a token whose role is unknown returns 401 INVALID_TOKEN")
    void updateStatus_unknownRole_returns401() throws Exception {
        String token = TestTokens.token(TestTokens.TEST_SECRET, DRIVER_ID.toString(), "ROOT", Instant.now().plusSeconds(60));

        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("PATCH status with a PASSENGER token returns 403 ACCESS_DENIED")
    void updateStatus_passengerToken_returns403() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "PASSENGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PATCH status of another driver with a DRIVER token returns 403 NOT_OWN_RESOURCE")
    void updateStatus_otherDriversProfile_returns403() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/status", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(UUID.randomUUID(), "DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("AVAILABLE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWN_RESOURCE"));

        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("/internal/** is not opened by the JWT chain: without X-Internal-Api-Key it is still 401")
    void internalApi_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(post("/internal/drivers")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_API_KEY"));
    }

    @Test
    @DisplayName("/internal/** with the internal key reaches the controller (validation answers 400)")
    void internalApi_withApiKey_reachesController() throws Exception {
        mockMvc.perform(post("/internal/drivers")
                        .header(InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, "test-internal-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "{0} /api/drivers/id/reserve")
    @ValueSource(strings = {"POST", "DELETE"})
    @DisplayName("/reserve without X-Internal-Api-Key returns 401 INVALID_INTERNAL_API_KEY")
    void reserve_withoutApiKey_returns401(String method) throws Exception {
        mockMvc.perform(reserveRequest(method))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_API_KEY"));

        verifyNoInteractions(driverRepository);
    }

    @ParameterizedTest(name = "{0} /api/drivers/id/reserve")
    @ValueSource(strings = {"POST", "DELETE"})
    @DisplayName("/reserve is not opened by a JWT: a DRIVER token without X-Internal-Api-Key is still 401")
    void reserve_driverTokenWithoutApiKey_returns401(String method) throws Exception {
        mockMvc.perform(reserveRequest(method)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_API_KEY"));
    }

    @Test
    @DisplayName("POST /reserve with the internal key and no token reserves the driver (200)")
    void reserve_withApiKey_returns200() throws Exception {
        when(driverRepository.reserve(eq(DRIVER_ID), any(UUID.class), eq(3), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        mockMvc.perform(reserveRequest("POST")
                        .header(InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, "test-internal-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    @DisplayName("DELETE /reserve with the internal key and no token releases the driver (204)")
    void release_withApiKey_returns204() throws Exception {
        when(driverRepository.releaseReservation(eq(DRIVER_ID), eq(TRIP_ID), any(Instant.class))).thenReturn(1);

        mockMvc.perform(reserveRequest("DELETE")
                        .header(InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, "test-internal-api-key"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("/reserve with a wrong internal key returns 401 INVALID_INTERNAL_API_KEY")
    void reserve_wrongApiKey_returns401() throws Exception {
        mockMvc.perform(reserveRequest("POST")
                        .header(InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_API_KEY"));

        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PATCH location with a PASSENGER token returns 403 ACCESS_DENIED")
    void updateLocation_passengerToken_returns403() throws Exception {
        mockMvc.perform(patch("/api/drivers/{id}/location", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "PASSENGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 21.03, \"longitude\": 105.85}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("PATCH own location with a DRIVER token returns 200")
    void updateLocation_ownDriverToken_returns200() throws Exception {
        when(driverRepository.updateLocation(any(), any(), any(), any())).thenReturn(1);

        mockMvc.perform(patch("/api/drivers/{id}/location", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 21.03, \"longitude\": 105.85}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.latitude").value(21.03));
    }

    @Test
    @DisplayName("GET /api/drivers/{id} without a token returns 401, with any role's token 200")
    void getDriver_requiresAnyAuthenticatedUser() throws Exception {
        Driver driver = Driver.builder().driverId(DRIVER_ID).fullName("Tran Van Binh").status(DriverStatus.AVAILABLE).build();
        when(driverRepository.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriver(driver)).thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/{id}", DRIVER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_TOKEN"));

        mockMvc.perform(get("/api/drivers/{id}", DRIVER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(UUID.randomUUID(), "PASSENGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Tran Van Binh"));
    }

    @Test
    @DisplayName("GET /api/drivers/nearby needs a PASSENGER token: 401 without, 403 with DRIVER, 200 with PASSENGER")
    void getNearby_requiresPassengerToken() throws Exception {
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/nearby").param("latitude", "21.0285").param("longitude", "105.8542"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/drivers/nearby").param("latitude", "21.0285").param("longitude", "105.8542")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/drivers/nearby").param("latitude", "21.0285").param("longitude", "105.8542")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(UUID.randomUUID(), "PASSENGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("HEAD /api/drivers/nearby is answered by the GET handler, so it follows the same role rule: 403 with DRIVER")
    void headNearby_driverToken_returns403AndRunsNoSearch() throws Exception {
        mockMvc.perform(head("/api/drivers/nearby").param("latitude", "21.0285").param("longitude", "105.8542")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(DRIVER_ID, "DRIVER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(driverRepository);
    }
}

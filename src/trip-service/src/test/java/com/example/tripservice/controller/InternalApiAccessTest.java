package com.example.tripservice.controller;

import com.example.tripservice.config.InternalApiKeyInterceptor;
import com.example.tripservice.exception.GlobalExceptionHandler;
import com.example.tripservice.service.TripService;
import com.example.tripservice.dto.TripResponse;
import com.example.tripservice.entity.TripStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalApiAccessTest {

    @Test
    void internalKeyCannotCompleteTripOrMasqueradeAsPassenger() throws Exception {
        var service = mock(TripService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TripController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new InternalApiKeyInterceptor("test-key"))
            .build();
        UUID trip = UUID.randomUUID();
        mvc.perform(post("/api/trips/{id}/complete", trip).header("X-Internal-Api-Key", "test-key"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/trips/{id}/cancel", trip).header("X-Internal-Api-Key", "wrong")
            .contentType("application/json")
            .content("{\"reason\":\"MATCHING_TIMEOUT\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/trips/{id}/cancel", trip).header("X-Internal-Api-Key", "test-key")
            .contentType("application/json")
            .content("{\"reason\":\"PASSENGER_CANCELLED\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void internalTimeoutUsesRestrictedServiceMethod_andMissingIdentityFails() throws Exception {
        var service = mock(TripService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TripController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new InternalApiKeyInterceptor("test-key"))
            .build();
        UUID trip = UUID.randomUUID();
        when(service.cancelForMatchingTimeout(trip))
            .thenReturn(TripResponse.builder().tripId(trip).status(TripStatus.CANCELLED).build());
        mvc.perform(post("/api/trips/{id}/cancel", trip).header("X-Internal-Api-Key", "test-key")
            .contentType("application/json")
            .content("{\"reason\":\"MATCHING_TIMEOUT\"}")).andExpect(status().isOk());
        verify(service).cancelForMatchingTimeout(trip);
        mvc.perform(get("/api/trips/{id}", trip)).andExpect(status().isUnauthorized());
    }

    @Test
    void passengerRoleCannotStart_andMalformedIdIsBadRequest() throws Exception {
        var service = mock(TripService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TripController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new InternalApiKeyInterceptor("test-key"))
            .build();
        mvc.perform(post("/api/trips/{id}/start", UUID.randomUUID()).header("X-User-Id", UUID.randomUUID())
            .header("X-User-Role", "PASSENGER")).andExpect(status().isForbidden());
        mvc.perform(get("/api/trips/not-a-uuid").header("X-User-Id", UUID.randomUUID()))
            .andExpect(status().isBadRequest());
    }

}

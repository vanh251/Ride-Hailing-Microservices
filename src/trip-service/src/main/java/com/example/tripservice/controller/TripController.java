package com.example.tripservice.controller;

import com.example.tripservice.config.OpenApiConfig;
import com.example.tripservice.dto.CancelTripRequest;
import com.example.tripservice.dto.CreateTripRequest;
import com.example.tripservice.dto.TripResponse;
import com.example.tripservice.exception.ErrorResponse;
import com.example.tripservice.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
@Tag(name = "Trips", description = "Trip lifecycle management and dispatching APIs")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class TripController {

    private final TripService tripService;

    @Operation(summary = "Create a ride request and trip",
            description = "Creates a new ride request and initiates driver search with SEARCHING status.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Trip created successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error (missing coordinates or invalid data)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @PostMapping
    public ResponseEntity<TripResponse> createTrip(@Valid @RequestBody CreateTripRequest request,
            @Parameter(description = "Distributed tracing correlation ID") @RequestHeader(value = "X-Correlation-ID",
                    required = false) String correlationId,
            @Parameter(description = "User ID of authenticated passenger") @RequestHeader(value = "X-User-Id",
                    required = false) UUID userId) {

        log.info("Received POST /api/trips request, correlationId={}, userId={}", correlationId, userId);
        TripResponse response = tripService.createTrip(request, correlationId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get trip by ID",
            description = "Retrieves trip details including status, passenger, driver, locations, and fare.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip retrieved successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @GetMapping("/{id}")
    public ResponseEntity<TripResponse> getTrip(
            @Parameter(description = "Unique ID of the trip") @PathVariable("id") UUID id) {
        log.info("Received GET /api/trips/{}", id);
        TripResponse response = tripService.getTrip(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Accept assigned trip",
            description = "Driver accepts the trip after being matched and assigned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip accepted successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid trip state transition",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @PostMapping("/{id}/accept")
    public ResponseEntity<TripResponse> acceptTrip(
            @Parameter(description = "Unique ID of the trip") @PathVariable("id") UUID id,
            @Parameter(description = "Driver ID from header") @RequestHeader(value = "X-User-Id",
                    required = false) UUID driverId) {

        UUID effectiveDriverId = driverId;
        log.info("Received POST /api/trips/{}/accept, driverId={}", id, effectiveDriverId);
        TripResponse response = tripService.acceptTrip(id, effectiveDriverId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Start trip",
            description = "Driver starts the trip upon picking up passenger. Transitions state to IN_PROGRESS.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip started successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Trip is not in ASSIGNED state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @PostMapping("/{id}/start")
    public ResponseEntity<TripResponse> startTrip(
            @Parameter(description = "Unique ID of the trip") @PathVariable("id") UUID id,
            @RequestHeader(value = "X-User-Id", required = false) UUID driverId) {
        log.info("Received POST /api/trips/{}/start", id);
        TripResponse response = tripService.startTrip(id, driverId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Complete trip",
            description = "Driver completes the trip upon arriving at destination. Transitions state to COMPLETED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip completed successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Trip is not in IN_PROGRESS state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @PostMapping("/{id}/complete")
    public ResponseEntity<TripResponse> completeTrip(
            @Parameter(description = "Unique ID of the trip") @PathVariable("id") UUID id,
            @RequestHeader(value = "X-User-Id", required = false) UUID driverId) {
        log.info("Received POST /api/trips/{}/complete", id);
        TripResponse response = tripService.completeTrip(id, driverId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Cancel trip",
            description = "Cancels a trip. Allowed only when trip status is REQUESTED, SEARCHING, or ASSIGNED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip cancelled successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "Trip cannot be cancelled in current state (e.g. IN_PROGRESS)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))) })
    @PostMapping("/{id}/cancel")
    public ResponseEntity<TripResponse> cancelTrip(
            @Parameter(description = "Unique ID of the trip") @PathVariable("id") UUID id,
            @RequestBody(required = false) CancelTripRequest request,
            @RequestHeader(value = "X-User-Id", required = false) UUID passengerId,
            jakarta.servlet.http.HttpServletRequest servletRequest) {

        log.info("Received POST /api/trips/{}/cancel", id);
        TripResponse response;
        if (Boolean.TRUE.equals(servletRequest.getAttribute("internalCall"))) {
            if (request == null || !"MATCHING_TIMEOUT".equals(request.getReason())) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Internal cancellation only supports MATCHING_TIMEOUT");
            }
            response = tripService.cancelForMatchingTimeout(id);
        }
        else {
            response = tripService.cancelTrip(id, request, passengerId);
        }
        return ResponseEntity.ok(response);
    }

}

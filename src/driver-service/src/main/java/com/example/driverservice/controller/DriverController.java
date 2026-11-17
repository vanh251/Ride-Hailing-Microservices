package com.example.driverservice.controller;

import com.example.driverservice.config.InternalApiKeyInterceptor;
import com.example.driverservice.dto.request.ReserveDriverRequest;
import com.example.driverservice.dto.request.UpdateDriverStatusRequest;
import com.example.driverservice.dto.request.UpdateLocationRequest;
import com.example.driverservice.dto.response.AvailableDriverResponse;
import com.example.driverservice.dto.response.DriverDetailResponse;
import com.example.driverservice.dto.response.DriverLocationResponse;
import com.example.driverservice.dto.response.DriverStatusResponse;
import com.example.driverservice.dto.response.ErrorResponse;
import com.example.driverservice.dto.response.NearbyDriverResponse;
import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
@Tag(name = "Drivers", description = "Driver availability, location and profile APIs")
public class DriverController {

    // Pagination of the two driver searches (docs/api.md §8): distance is the only sortable field
    private static final String DEFAULT_SORT = "distanceKm,asc";
    private static final String SORT_PATTERN = "distanceKm(,(asc|desc))?";
    private static final String SORT_MESSAGE = "sort must be distanceKm,asc or distanceKm,desc";

    private final DriverService driverService;

    @Operation(summary = "List AVAILABLE drivers near a point",
            description = "AVAILABLE drivers within radiusKm of the point, nearest first, one page of at most 50 "
                    + "(page, size, sort; limit is the older name of size). "
                    + "Each item carries the version used for the CAS reservation. "
                    + "Called by matching-service without a token, so the point and the caps keep it from listing the whole fleet.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Available drivers",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = AvailableDriverResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Missing or out-of-range parameter",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/available")
    public ResponseEntity<List<AvailableDriverResponse>> getAvailableDrivers(
            @Parameter(description = "Reference point latitude", example = "21.0285")
            @RequestParam
            @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90") double latitude,
            @Parameter(description = "Reference point longitude", example = "105.8542")
            @RequestParam
            @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180") double longitude,
            @Parameter(description = "Search radius in km (max 50)", example = "5.0")
            @RequestParam(defaultValue = "5.0")
            @Positive(message = "radiusKm must be greater than 0")
            @DecimalMax(value = "50.0", message = "radiusKm must be at most 50") double radiusKm,
            @Parameter(description = "Page size when size is not sent (1-50)", example = "20")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "limit must be between 1 and 50")
            @Max(value = 50, message = "limit must be between 1 and 50") int limit,
            @Parameter(description = "Page number, starting at 0", example = "0")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater") int page,
            @Parameter(description = "Page size (1-50); takes precedence over limit", example = "10")
            @RequestParam(required = false)
            @Min(value = 1, message = "size must be between 1 and 50")
            @Max(value = 50, message = "size must be between 1 and 50") Integer size,
            @Parameter(description = "distanceKm,asc (nearest first, default) or distanceKm,desc", example = "distanceKm,asc")
            @RequestParam(defaultValue = DEFAULT_SORT)
            @Pattern(regexp = SORT_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = SORT_MESSAGE) String sort) {
        return ResponseEntity.ok(driverService.findAvailableDrivers(latitude, longitude, radiusKm,
                page, pageSize(size, limit), farthestFirst(sort)));
    }

    @Operation(summary = "Find AVAILABLE drivers near a pickup point",
            description = "AVAILABLE drivers within radiusKm, nearest first, one page of at most 50 "
                    + "(page, size, sort; limit is the older name of size). Requires a PASSENGER token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nearby drivers",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = NearbyDriverResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Missing or out-of-range parameter",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a PASSENGER token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/nearby")
    public ResponseEntity<List<NearbyDriverResponse>> getNearbyDrivers(
            @Parameter(description = "Pickup latitude", example = "21.0285")
            @RequestParam
            @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
            @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90") double latitude,
            @Parameter(description = "Pickup longitude", example = "105.8542")
            @RequestParam
            @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
            @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180") double longitude,
            @Parameter(description = "Search radius in km (max 50)", example = "5.0")
            @RequestParam(defaultValue = "5.0")
            @Positive(message = "radiusKm must be greater than 0")
            @DecimalMax(value = "50.0", message = "radiusKm must be at most 50") double radiusKm,
            @Parameter(description = "Page size when size is not sent (1-50)", example = "10")
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "limit must be between 1 and 50")
            @Max(value = 50, message = "limit must be between 1 and 50") int limit,
            @Parameter(description = "Page number, starting at 0", example = "0")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater") int page,
            @Parameter(description = "Page size (1-50); takes precedence over limit", example = "10")
            @RequestParam(required = false)
            @Min(value = 1, message = "size must be between 1 and 50")
            @Max(value = 50, message = "size must be between 1 and 50") Integer size,
            @Parameter(description = "distanceKm,asc (nearest first, default) or distanceKm,desc", example = "distanceKm,asc")
            @RequestParam(defaultValue = DEFAULT_SORT)
            @Pattern(regexp = SORT_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = SORT_MESSAGE) String sort) {
        return ResponseEntity.ok(driverService.findNearbyDrivers(latitude, longitude, radiusKm,
                page, pageSize(size, limit), farthestFirst(sort)));
    }

    // size is the name of docs/api.md §8; limit is the older name of the same value and stays for existing callers
    private static int pageSize(Integer size, int limit) {
        return size != null ? size : limit;
    }

    private static boolean farthestFirst(String sort) {
        return sort.toLowerCase(Locale.ROOT).endsWith(",desc");
    }

    @Operation(summary = "Update own availability status",
            description = "A driver switches between OFFLINE and AVAILABLE. RESERVED and BUSY are set by the system only. "
                    + "Requires a DRIVER token whose user id equals {id}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated (or already the requested one)",
                    content = @Content(schema = @Schema(implementation = DriverStatusResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or unknown status value",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a DRIVER token, or not the caller's own profile",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Driver profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The driver was changed concurrently (e.g. just reserved); retry",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Transition not allowed (to/from RESERVED or BUSY)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}/status")
    public ResponseEntity<DriverStatusResponse> updateStatus(
            @Parameter(description = "Driver id (= the driver's user id)") @PathVariable UUID id,
            @Valid @RequestBody UpdateDriverStatusRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UUID callerId) {
        return ResponseEntity.ok(driverService.updateStatus(id, request.getStatus(), callerId));
    }

    @Operation(summary = "Report own position (simulated GPS)",
            description = "Stores the driver's current position and heartbeat. Does not change the driver's version, "
                    + "so position reports never make a reservation fail. Requires a DRIVER token whose user id equals {id}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Position stored",
                    content = @Content(schema = @Schema(implementation = DriverLocationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or out-of-range coordinates",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a DRIVER token, or not the caller's own profile",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Driver profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}/location")
    public ResponseEntity<DriverLocationResponse> updateLocation(
            @Parameter(description = "Driver id (= the driver's user id)") @PathVariable UUID id,
            @Valid @RequestBody UpdateLocationRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UUID callerId) {
        return ResponseEntity.ok(driverService.updateLocation(id, request.getLatitude(), request.getLongitude(), callerId));
    }

    @Operation(summary = "Get driver details",
            description = "Profile, active vehicle and last known position. Any authenticated user; "
                    + "the position of an OFFLINE driver is shown only to that driver.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Driver found",
                    content = @Content(schema = @Schema(implementation = DriverDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Id is not a UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Driver not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<DriverDetailResponse> getDriver(
            @Parameter(description = "Driver id (= the driver's user id)") @PathVariable UUID id,
            @Parameter(hidden = true) @AuthenticationPrincipal UUID callerId) {
        return ResponseEntity.ok(driverService.getDriver(id, callerId));
    }

    @Operation(summary = "Reserve a driver for a trip (internal)",
            description = "Compare-and-swap AVAILABLE -> RESERVED: succeeds only if the driver is AVAILABLE and its version "
                    + "equals expectedVersion, so of several concurrent requests one wins. The reservation is a lease: "
                    + "a driver not assigned before reservedUntil returns to AVAILABLE. Called by matching-service with "
                    + "the X-Internal-Api-Key header instead of a JWT; not routed by the API Gateway.")
    @SecurityRequirements
    @Parameter(name = InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, in = ParameterIn.HEADER, required = true,
            description = "Shared internal API key (INTERNAL_API_KEY)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Driver reserved",
                    content = @Content(schema = @Schema(implementation = ReserveDriverResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or invalid tripId / expectedVersion, or id is not a UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Driver not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Driver is not AVAILABLE or its version changed (DRIVER_ALREADY_RESERVED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReserveDriverResponse> reserveDriver(
            @Parameter(description = "Driver id (= the driver's user id)") @PathVariable UUID id,
            @Valid @RequestBody ReserveDriverRequest request) {
        return ResponseEntity.ok(driverService.reserveDriver(id, request.getTripId(), request.getExpectedVersion()));
    }

    @Operation(summary = "Release a driver reservation (internal)",
            description = "Compensating action RESERVED -> AVAILABLE. Idempotent: a driver that is not RESERVED is left "
                    + "unchanged and the call still returns 204. Called by matching-service with the X-Internal-Api-Key "
                    + "header instead of a JWT; not routed by the API Gateway.")
    @SecurityRequirements
    @Parameter(name = InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER, in = ParameterIn.HEADER, required = true,
            description = "Shared internal API key (INTERNAL_API_KEY)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Driver is no longer reserved"),
            @ApiResponse(responseCode = "400", description = "Id is not a UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Driver not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}/reserve")
    public ResponseEntity<Void> releaseReservation(
            @Parameter(description = "Driver id (= the driver's user id)") @PathVariable UUID id) {
        driverService.releaseReservation(id);
        return ResponseEntity.noContent().build();
    }
}

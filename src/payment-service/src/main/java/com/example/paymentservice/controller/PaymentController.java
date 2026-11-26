package com.example.paymentservice.controller;

import com.example.paymentservice.dto.request.PaymentChargeRequest;
import com.example.paymentservice.dto.response.ErrorResponse;
import com.example.paymentservice.dto.response.PaymentResponse;
import com.example.paymentservice.exception.BusinessException;
import com.example.paymentservice.payos.CheckoutIdentity;
import com.example.paymentservice.service.PaymentService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment charging, retrieval, and refund APIs")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;
    private final CheckoutIdentity identity;

    @Operation(summary = "Pay a completed trip",
            description = "The trip's passenger pays the amount due with a simulated transfer. Repeating the call "
                    + "returns the first result instead of paying again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer done (SUCCESS) or declined by the simulator (FAILED)",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid passenger token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a passenger, or not this trip's passenger",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment is due for this trip yet",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{tripId}/pay")
    public ResponseEntity<PaymentResponse> payTrip(
            @Parameter(description = "ID of the completed trip") @PathVariable UUID tripId,
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        var user = identity.verify(bearer);
        if (!"PASSENGER".equals(user.role())) {
            throw new BusinessException("Only a passenger can pay a trip", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        return ResponseEntity.ok(paymentService.payTrip(tripId, user.id()));
    }

    @Operation(summary = "Charge payment",
            description = "Charges the passenger for a trip. Repeating the call with the same idempotency key "
                    + "returns the first result instead of charging again.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment charged successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload, trip ID or missing idempotency key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "402", description = "Simulated payment failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused for a different charge, or trip already paid",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{tripId}/charge")
    public ResponseEntity<PaymentResponse> chargePayment(
            @Parameter(description = "ID of the trip being charged", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID tripId,
            @Parameter(description = "Idempotency key preventing duplicate charges (required unless sent in the body)",
                    example = "trip-001-charge-001")
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Parameter(description = "Correlation ID for distributed tracing", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody PaymentChargeRequest request) {

        PaymentResponse response = paymentService.charge(tripId, request, idempotencyKeyHeader, correlationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get payment details", description = "Retrieves the latest payment record of a trip")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment found",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Trip ID is not a UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Payment not found for given trip",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{tripId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @Parameter(description = "ID of the trip", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID tripId) {
        PaymentResponse response = paymentService.getPaymentByTripId(tripId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Refund payment", description = "Refunds the successful payment of a trip (compensating action)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment refunded successfully",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Trip ID is not a UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Payment not found for given trip",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Payment cannot be refunded in its current state, or was changed concurrently",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{tripId}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @Parameter(description = "ID of the trip to refund", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID tripId,
            @Parameter(description = "Correlation ID for distributed tracing", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        PaymentResponse response = paymentService.refund(tripId, correlationId);
        return ResponseEntity.ok(response);
    }
}

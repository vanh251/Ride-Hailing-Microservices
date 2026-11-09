package com.example.tripservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(400)
                .error("Bad Request")
                .message("Malformed request or invalid parameter")
                .code("VALIDATION_ERROR")
                .path(request.getRequestURI())
                .correlationId(resolveCorrelationId(request))
                .build());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(org.springframework.web.server.ResponseStatusException ex,
            HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        return ResponseEntity.status(status)
            .body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status)
                .error(org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase())
                .message(ex.getReason())
                .code("INVALID_INTERNAL_API_KEY".equals(ex.getReason()) ? "INVALID_INTERNAL_API_KEY"
                        : status == 401 ? "UNAUTHORIZED" : status == 403 ? "FORBIDDEN" : "VALIDATION_ERROR")
                .path(request.getRequestURI())
                .correlationId(resolveCorrelationId(request))
                .build());
    }

    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(org.springframework.web.client.RestClientException ex,
            HttpServletRequest request) {
        log.error("Downstream call failed on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(503)
            .body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(503)
                .error("Service Unavailable")
                .message("A required service is unavailable")
                .code("DOWNSTREAM_UNAVAILABLE")
                .path(request.getRequestURI())
                .correlationId(resolveCorrelationId(request))
                .build());
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(
            org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest request) {
        return ResponseEntity.status(409)
            .body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(409)
                .error("Conflict")
                .message("Concurrent update; retry request")
                .code("CONCURRENT_UPDATE")
                .path(request.getRequestURI())
                .correlationId(resolveCorrelationId(request))
                .build());
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTripNotFound(TripNotFoundException ex, HttpServletRequest request) {
        log.warn("Trip not found: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.NOT_FOUND.value())
            .error(HttpStatus.NOT_FOUND.getReasonPhrase())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(RideRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRideRequestNotFound(RideRequestNotFoundException ex,
            HttpServletRequest request) {
        log.warn("Ride request not found: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.NOT_FOUND.value())
            .error(HttpStatus.NOT_FOUND.getReasonPhrase())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(InvalidTripStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTripState(InvalidTripStateException ex,
            HttpServletRequest request) {
        log.warn("Invalid trip state transition: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.CONFLICT.value())
            .error(HttpStatus.CONFLICT.getReasonPhrase())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String details = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        log.warn("Validation error on {}: {}", request.getRequestURI(), details);
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
            .message("Validation failed: " + details)
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
            HttpServletRequest request) {
        log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
            .message("An unexpected internal error occurred")
            .path(request.getRequestURI())
            .correlationId(resolveCorrelationId(request))
            .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            Object attr = request.getAttribute("X-Correlation-ID");
            if (attr != null) {
                correlationId = attr.toString();
            }
            else {
                correlationId = UUID.randomUUID().toString();
            }
        }
        return correlationId;
    }

}

package com.example.tripservice.service;

import com.example.tripservice.client.*;
import com.example.tripservice.dto.*;
import com.example.tripservice.entity.*;
import com.example.tripservice.event.*;
import com.example.tripservice.exception.*;
import com.example.tripservice.mapper.TripMapper;
import com.example.tripservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClientException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;

    private final RideRequestRepository rideRequestRepository;

    private final TripStatusHistoryRepository tripStatusHistoryRepository;

    private final TripMapper tripMapper;

    private final TripEventPublisher eventPublisher;

    private final SagaLogRepository sagaLogRepository;

    private final PricingServiceClient pricingServiceClient;

    private final DriverServiceClient driverServiceClient;

    private final Clock clock;

    @Override
    @Transactional
    public TripResponse createTrip(CreateTripRequest request, String correlationId, UUID passengerId) {
        authenticated(passengerId);
        String correlation = (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString()
                : correlationId;
        try {
            UUID.fromString(correlation);
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Correlation-ID must be a UUID");
        }
        var quote = pricingServiceClient.getQuote(request.getPickup(), request.getDestination());
        if (quote == null)
            throw new RestClientException("Missing Pricing quote");
        var ride = RideRequest.builder()
            .passengerId(passengerId)
            .pickupAddress(request.getPickup().getAddress())
            .pickupLat(request.getPickup().getLatitude())
            .pickupLng(request.getPickup().getLongitude())
            .dropoffAddress(request.getDestination().getAddress())
            .dropoffLat(request.getDestination().getLatitude())
            .dropoffLng(request.getDestination().getLongitude())
            .status("PENDING")
            .correlationId(correlation)
            .build();
        ride = rideRequestRepository.save(ride);
        var trip = Trip.builder()
            .rideRequestId(ride.getRequestId())
            .passengerId(passengerId)
            .status(TripStatus.SEARCHING)
            .pickupAddress(ride.getPickupAddress())
            .pickupLat(ride.getPickupLat())
            .pickupLng(ride.getPickupLng())
            .dropoffAddress(ride.getDropoffAddress())
            .dropoffLat(ride.getDropoffLat())
            .dropoffLng(ride.getDropoffLng())
            .distanceKm(quote.estimatedDistanceKm())
            .estimatedFare(quote.totalFare())
            .currency(quote.currency())
            .correlationId(correlation)
            .build();
        trip = tripRepository.save(trip);
        ride.setTripId(trip.getTripId());
        ride.setStatus("SEARCHING");
        rideRequestRepository.save(ride);
        history(trip, TripStatus.REQUESTED, TripStatus.SEARCHING, "Ride requested");
        saga(trip, "RideRequested", "STARTED", "{}");
        publish("RideRequested", trip, null,
                Map.of("tripId", trip.getTripId(), "passengerId", passengerId, "pickup", request.getPickup(),
                        "destination", request.getDestination(), "estimatedFare", trip.getEstimatedFare(),
                        "requestedAt", trip.getCreatedAt()));
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional(readOnly = true)
    public TripResponse getTrip(UUID tripId) {
        return tripMapper.toResponse(load(tripId));
    }

    @Override
    @Transactional
    public TripResponse acceptTrip(UUID tripId, UUID driverId) {
        var trip = loadForUpdate(tripId);
        if (trip.getStatus() != TripStatus.ASSIGNED)
            throw new InvalidTripStateException(trip.getStatus(), TripStatus.ASSIGNED);
        requireDriver(trip, driverId);
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional
    public TripResponse startTrip(UUID tripId, UUID driverId) {
        var trip = loadForUpdate(tripId);
        requireDriver(trip, driverId);
        if (trip.getStatus() != TripStatus.ASSIGNED)
            throw new InvalidTripStateException(trip.getStatus(), TripStatus.IN_PROGRESS);
        transition(trip, TripStatus.IN_PROGRESS, "Driver started trip");
        trip.setStartedAt(clock.instant());
        tripRepository.save(trip);
        publish("TripStarted", trip, null,
                Map.of("tripId", tripId, "driverId", trip.getDriverId(), "passengerId", trip.getPassengerId(),
                        "startedAt", trip.getStartedAt(), "driverLocation",
                        Map.of("latitude", trip.getPickupLat(), "longitude", trip.getPickupLng())));
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional
    public TripResponse completeTrip(UUID tripId, UUID driverId) {
        var trip = loadForUpdate(tripId);
        requireDriver(trip, driverId);
        if (trip.getStatus() != TripStatus.IN_PROGRESS)
            throw new InvalidTripStateException(trip.getStatus(), TripStatus.COMPLETED);
        Instant completedAt = clock.instant();
        if (trip.getStartedAt() == null || trip.getStartedAt().isAfter(completedAt) || trip.getDistanceKm() == null
                || !Double.isFinite(trip.getDistanceKm()) || trip.getDistanceKm() < 0)
            throw new InvalidTripStateException("Trip has no valid route/start metrics");
        int duration = Math.toIntExact(Math.max(1, Duration.between(trip.getStartedAt(), completedAt).toMinutes()));
        var fare = pricingServiceClient.getFinalFare(tripId, trip.getDistanceKm(), duration);
        if (fare == null || fare.totalFare() == null || fare.totalFare().signum() <= 0
                || !trip.getCurrency().equals(fare.currency()))
            throw new RestClientException("Invalid final fare");
        trip.setFinalFare(fare.totalFare());
        trip.setCompletedAt(completedAt);
        transition(trip, TripStatus.COMPLETED, "Driver completed trip");
        tripRepository.save(trip);
        saga(trip, "TripCompleted", "COMPLETED", "{}");
        publish("TripCompleted", trip, null,
                Map.of("tripId", tripId, "driverId", trip.getDriverId(), "passengerId", trip.getPassengerId(),
                        "actualDistanceKm", trip.getDistanceKm(), "actualDurationMin", duration, "finalFare",
                        trip.getFinalFare(), "currency", trip.getCurrency(), "completedAt", completedAt));
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional
    public TripResponse cancelTrip(UUID tripId, CancelTripRequest request, UUID passengerId) {
        authenticated(passengerId);
        var trip = loadForUpdate(tripId);
        if (!passengerId.equals(trip.getPassengerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the trip passenger");
        String detail = request != null && request.getReason() != null ? request.getReason() : "Passenger cancelled";
        return cancel(trip, "PASSENGER_CANCELLED", detail);
    }

    @Override
    @Transactional
    public TripResponse cancelForMatchingTimeout(UUID tripId) {
        var trip = loadForUpdate(tripId);
        if (trip.getStatus() == TripStatus.CANCELLED)
            return tripMapper.toResponse(trip);
        if (trip.getStatus() != TripStatus.SEARCHING)
            throw new InvalidTripStateException("Timeout applies only to SEARCHING trips");
        return cancel(trip, "MATCHING_TIMEOUT", "Matching search exhausted");
    }

    private TripResponse cancel(Trip trip, String reason, String detail) {
        if (trip.getStatus() == TripStatus.CANCELLED)
            return tripMapper.toResponse(trip);
        if (trip.getStatus() != TripStatus.SEARCHING && trip.getStatus() != TripStatus.ASSIGNED)
            throw new InvalidTripStateException("Cannot cancel trip in " + trip.getStatus());
        TripStatus previous = trip.getStatus();
        if (trip.getDriverId() != null) {
            try {
                driverServiceClient.releaseDriverReservation(trip.getDriverId(), trip.getTripId());
            }
            catch (RestClientException ex) {
                log.error("Release failed trip={}; TripCancelled/lease must compensate", trip.getTripId(), ex);
            }
        }
        transition(trip, TripStatus.CANCELLED, detail);
        trip.setCancellationReason(detail);
        trip.setCancelledAt(clock.instant());
        tripRepository.save(trip);
        if (trip.getRideRequestId() != null)
            rideRequestRepository.findById(trip.getRideRequestId()).ifPresent(r -> {
                r.setStatus("CANCELLED");
                rideRequestRepository.save(r);
            });
        var payload = new LinkedHashMap<String, Object>();
        payload.put("tripId", trip.getTripId());
        payload.put("passengerId", trip.getPassengerId());
        if (trip.getDriverId() != null)
            payload.put("driverId", trip.getDriverId());
        payload.put("previousStatus", previous.name());
        payload.put("reason", reason);
        payload.put("cancelledAt", trip.getCancelledAt());
        publish("TripCancelled", trip, null, payload);
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional
    public TripResponse assignDriver(UUID tripId, UUID driverId, UUID sourceEventId) {
        var trip = loadForUpdate(tripId);
        if (trip.getStatus() != TripStatus.SEARCHING) {
            log.info("Ignoring assignment for trip={} status={}", tripId, trip.getStatus());
            return tripMapper.toResponse(trip);
        }
        if (driverId == null || sourceEventId == null)
            throw new ValidationException("Assignment requires driver and source event");
        trip.setDriverId(driverId);
        transition(trip, TripStatus.ASSIGNED, "Driver reserved");
        tripRepository.save(trip);
        saga(trip, "DriverReserved", "DRIVER_RESERVED", "{\"driverId\":\"" + driverId + "\"}");
        publish("DriverAssigned", trip, sourceEventId, Map.of("tripId", tripId, "driverId", driverId, "passengerId",
                trip.getPassengerId(), "assignedAt", clock.instant()));
        return tripMapper.toResponse(trip);
    }

    @Override
    @Transactional
    public void applyPaymentResult(UUID tripId, UUID paymentId, UUID passengerId, BigDecimal amount, String currency,
            boolean succeeded, String reason) {
        var trip = loadForUpdate(tripId);
        if (trip.getStatus() != TripStatus.COMPLETED) {
            log.info("Ignoring payment result payment={} trip={} status={}", paymentId, tripId, trip.getStatus());
            return;
        }
        if (!trip.getPassengerId().equals(passengerId) || !trip.getCurrency().equals(currency)
                || trip.getFinalFare() == null || trip.getFinalFare().compareTo(amount) != 0)
            throw new ValidationException("Payment does not match completed trip");
        transition(trip, succeeded ? TripStatus.PAID : TripStatus.PAYMENT_FAILED,
                succeeded ? "Payment succeeded" : reason);
        tripRepository.save(trip);
        saga(trip, "PaymentResult", succeeded ? "SAGA_COMPLETED" : "COMPENSATED",
                "{\"paymentId\":\"" + paymentId + "\"}");
        if (!succeeded)
            log.warn("Trip payment debt recorded trip={} payment={} reason={}", tripId, paymentId, reason);
    }

    private Trip load(UUID id) {
        return tripRepository.findById(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    private Trip loadForUpdate(UUID id) {
        return tripRepository.findByIdForUpdate(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    private static void authenticated(UUID id) {
        if (id == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated identity is required");
    }

    private static void requireDriver(Trip trip, UUID driverId) {
        authenticated(driverId);
        if (!driverId.equals(trip.getDriverId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the assigned driver");
    }

    private void transition(Trip trip, TripStatus next, String reason) {
        TripStatus previous = trip.getStatus();
        if (!previous.canTransitionTo(next))
            throw new InvalidTripStateException(previous, next);
        trip.setStatus(next);
        history(trip, previous, next, reason);
    }

    private void history(Trip trip, TripStatus from, TripStatus to, String reason) {
        tripStatusHistoryRepository.save(TripStatusHistory.builder()
            .tripId(trip.getTripId())
            .fromStatus(from)
            .toStatus(to)
            .reason(reason)
            .changedAt(clock.instant())
            .build());
    }

    private void saga(Trip trip, String step, String status, String payload) {
        sagaLogRepository.save(SagaLog.builder()
            .id(UUID.randomUUID())
            .tripId(trip.getTripId())
            .stepName(step)
            .status(status)
            .payload(payload)
            .createdAt(clock.instant())
            .build());
    }

    private void publish(String type, Trip trip, UUID source, Object payload) {
        eventPublisher.publish(EventEnvelope.builder()
            .eventId(UUID.randomUUID())
            .eventType(type)
            .aggregateId(trip.getTripId().toString())
            .aggregateType("Trip")
            .producer("trip-service")
            .correlationId(trip.getCorrelationId())
            .causationId(source == null ? null : source.toString())
            .occurredAt(clock.instant())
            .payload(payload)
            .build());
    }

}

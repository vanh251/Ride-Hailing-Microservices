package com.example.matchingservice.service;

import com.example.matchingservice.client.*;
import com.example.matchingservice.dto.*;
import com.example.matchingservice.entity.*;
import com.example.matchingservice.event.*;
import com.example.matchingservice.exception.*;
import com.example.matchingservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import java.time.*;
import java.util.*;
import static com.example.matchingservice.event.EventValues.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingServiceImpl implements MatchingService {

    private static final String CONSUMER = "matching-service.trip-events";

    private final MatchRequestRepository matchRequestRepository;

    private final DriverOfferRepository driverOfferRepository;

    private final DriverServiceClient driverServiceClient;

    private final MatchingEventPublisher eventPublisher;

    private final ProcessedEventRepository processedEventRepository;

    private final TripServiceClient tripServiceClient;

    private final Clock clock;

    @Value("${matching.max-attempts:3}")
    private int maxAttempts = 3;

    @Value("${matching.search-timeout-seconds:60}")
    private int searchTimeoutSeconds = 60;

    @Value("${matching.search-radius-km:5}")
    private double radiusKm = 5;

    @Override
    @Transactional
    public void handleRideRequested(EventEnvelope<Map<String, Object>> event) {
        validate(event);
        var id = new ProcessedEventId(event.getEventId(), CONSUMER);
        if (processedEventRepository.existsById(id))
            return;
        var p = event.getPayload();
        UUID tripId = uuid(p, "tripId"), passengerId = uuid(p, "passengerId");
        Instant requestedAt = time(p, "requestedAt");
        if (!tripId.toString().equals(event.getAggregateId()))
            throw new ValidationException("Trip aggregate mismatch");
        var pickup = object(p, "pickup");
        double lat = number(pickup, "latitude"), lng = number(pickup, "longitude");
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new ValidationException("Pickup outside coordinate range");
        if (!processedEventRepository.existsByResultSummary("TripCancelled:" + tripId)) {
            var request = matchRequestRepository.findByIdForUpdate(tripId).orElse(null);
            if (request == null) {
                request = MatchRequest.builder()
                    .requestId(tripId)
                    .tripId(tripId)
                    .passengerId(passengerId)
                    .pickupLat(lat)
                    .pickupLng(lng)
                    .searchRadiusKm(radiusKm)
                    .correlationId(event.getCorrelationId())
                    .status(MatchStatus.PENDING)
                    .attemptCount(1)
                    .expiresAt(requestedAt.plusSeconds(searchTimeoutSeconds))
                    .build();
                request = matchRequestRepository.saveAndFlush(request);
                runMatch(request, event.getEventId().toString());
            }
        }
        processedEventRepository.save(ProcessedEvent.builder()
            .id(id)
            .processedAt(clock.instant())
            .resultSummary("RideRequested:" + tripId)
            .build());
    }

    @Override
    @Transactional
    public void handleTripCancelled(EventEnvelope<Map<String, Object>> event) {
        validate(event);
        var id = new ProcessedEventId(event.getEventId(), CONSUMER);
        if (processedEventRepository.existsById(id))
            return;
        UUID tripId = uuid(event.getPayload(), "tripId");
        matchRequestRepository.findByIdForUpdate(tripId).ifPresent(r -> {
            if (r.getMatchedDriverId() != null)
                driverServiceClient.releaseDriverReservation(r.getMatchedDriverId(), tripId);
            r.setStatus(MatchStatus.EXPIRED);
            matchRequestRepository.save(r);
        });
        processedEventRepository.save(ProcessedEvent.builder()
            .id(id)
            .processedAt(clock.instant())
            .resultSummary("TripCancelled:" + tripId)
            .build());
    }

    @Override
    @Transactional
    public void handleAssignmentRecovery(EventEnvelope<Map<String, Object>> event) {
        validate(event);
        var eventKey = new ProcessedEventId(event.getEventId(), CONSUMER);
        if (processedEventRepository.existsById(eventKey))
            return;
        if (!"Trip".equals(event.getAggregateType()) || !"trip-service".equals(event.getProducer()))
            throw new ValidationException("Invalid recovery producer");
        var payload = event.getPayload();
        UUID tripId = uuid(payload, "tripId");
        UUID source = uuid(payload, "sourceDriverReservedEventId");
        String reason = text(payload, "reason");
        if (!("LEASE_EXPIRED".equals(reason) || "DRIVER_REJECTED".equals(reason))
                || !source.toString().equals(event.getCausationId()))
            throw new ValidationException("Invalid assignment recovery reason/causation");
        if (!tripId.toString().equals(event.getAggregateId()))
            throw new ValidationException("Trip aggregate mismatch");
        var request = required(tripId);
        if (request.getStatus() == MatchStatus.MATCHED && source.equals(request.getActiveReservationEventId())) {
            if (!"SEARCHING".equals(tripServiceClient.getTripStatus(tripId))) {
                request.setStatus(MatchStatus.EXPIRED);
                matchRequestRepository.save(request);
            }
            else if (exhausted(request)) {
                tripServiceClient.cancelForMatchingTimeout(tripId, request.getCorrelationId());
                request.setStatus(MatchStatus.EXPIRED);
                matchRequestRepository.save(request);
            }
            else {
                request.setStatus(MatchStatus.NO_DRIVER_FOUND);
                request.setMatchedDriverId(null);
                request.setActiveReservationEventId(null);
                request.setActiveReservationVersion(null);
                request.setActiveReservedUntil(null);
                rematchDriver(tripId, request.getCorrelationId());
            }
        }
        processedEventRepository.save(ProcessedEvent.builder().id(eventKey).processedAt(clock.instant())
            .resultSummary("AssignmentRecoveryRequested:" + tripId).build());
    }

    @Override
    @Transactional
    public MatchResponse matchDriver(UUID requestId, String correlationId) {
        var r = required(requestId);
        if (r.getStatus() == MatchStatus.MATCHED)
            return response(r);
        if (r.getStatus() != MatchStatus.PENDING)
            throw new MatchingFailedException("Use rematch for an unsuccessful search");
        return runMatch(r, null);
    }

    @Override
    @Transactional
    public RematchResponse rematchDriver(UUID requestId, String correlationId) {
        var r = required(requestId);
        if (r.getStatus() != MatchStatus.NO_DRIVER_FOUND && r.getStatus() != MatchStatus.PENDING)
            throw new MatchingFailedException("Cannot replace a committed assignment");
        if (exhausted(r))
            throw new MatchingFailedException("Matching search budget exhausted");
        if (!"SEARCHING".equals(tripServiceClient.getTripStatus(r.getTripId())))
            throw new MatchingFailedException("Trip is no longer searching");
        if (r.getMatchedDriverId() != null) {
            r.setMatchedDriverId(null);
        }
        r.setAttemptCount(r.getAttemptCount() + 1);
        var result = runMatch(r, sourceEvent(r));
        return RematchResponse.builder()
            .requestId(requestId)
            .status(result.getStatus().name())
            .attempt(r.getAttemptCount())
            .matchedDriverId(result.getMatchedDriverId())
            .message("Rematch attempted")
            .build();
    }

    @Override
    @Transactional
    public void retryOrExpire(UUID requestId) {
        var r = required(requestId);
        if (r.getStatus() == MatchStatus.MATCHED || r.getStatus() == MatchStatus.EXPIRED)
            return;
        String before = org.slf4j.MDC.get("correlationId");
        org.slf4j.MDC.put("correlationId", r.getCorrelationId());
        try {
            if (exhausted(r)) {
                String status = tripServiceClient.getTripStatus(r.getTripId());
                if ("SEARCHING".equals(status))
                    tripServiceClient.cancelForMatchingTimeout(r.getTripId(), r.getCorrelationId());
                r.setStatus(MatchStatus.EXPIRED);
                matchRequestRepository.save(r);
            }
            else if (r.getStatus() == MatchStatus.NO_DRIVER_FOUND) {
                rematchDriver(requestId, r.getCorrelationId());
            }
        }
        finally {
            if (before == null)
                org.slf4j.MDC.remove("correlationId");
            else
                org.slf4j.MDC.put("correlationId", before);
        }
    }

    private MatchRequest required(UUID id) {
        return matchRequestRepository.findByIdForUpdate(id).orElseThrow(() -> new MatchRequestNotFoundException(id));
    }

    private boolean exhausted(MatchRequest r) {
        return r.getAttemptCount() >= maxAttempts || !clock.instant().isBefore(r.getExpiresAt());
    }

    private String sourceEvent(MatchRequest r) {
        return processedEventRepository.findFirstByResultSummaryOrderByProcessedAtAsc("RideRequested:" + r.getTripId())
            .map(p -> p.getId().getEventId().toString())
            .orElse(null);
    }

    private MatchResponse response(MatchRequest r) {
        return MatchResponse.builder()
            .requestId(r.getRequestId())
            .status(r.getStatus())
            .matchedDriverId(r.getMatchedDriverId())
            .message(r.getStatus().name())
            .build();
    }

    private boolean eligible(AvailableDriverDto d, double radius) {
        return d != null && d.getId() != null && "AVAILABLE".equals(d.getStatus()) && d.getVersion() != null
                && d.getVersion() >= 0 && d.getDistanceKm() != null && Double.isFinite(d.getDistanceKm())
                && d.getDistanceKm() >= 0 && d.getDistanceKm() <= radius && d.getFullName() != null
                && d.getLocation() != null && d.getLocation().getLatitude() != null
                && d.getLocation().getLongitude() != null && Double.isFinite(d.getLocation().getLatitude())
                && Double.isFinite(d.getLocation().getLongitude()) && Math.abs(d.getLocation().getLatitude()) <= 90
                && Math.abs(d.getLocation().getLongitude()) <= 180;
    }

    private MatchResponse runMatch(MatchRequest r, String causationId) {
        if (!clock.instant().isBefore(r.getExpiresAt())) {
            r.setStatus(MatchStatus.NO_DRIVER_FOUND);
            matchRequestRepository.save(r);
            return response(r);
        }
        r.setStatus(MatchStatus.MATCHING);
        var candidates = driverServiceClient
            .getAvailableDrivers(r.getPickupLat(), r.getPickupLng(), r.getSearchRadiusKm())
            .stream()
            .filter(d -> eligible(d, r.getSearchRadiusKm()))
            .sorted(Comparator.comparing(AvailableDriverDto::getDistanceKm).thenComparing(AvailableDriverDto::getId))
            .toList();
        int rank = 0;
        for (var d : candidates) {
            if (!clock.instant().isBefore(r.getExpiresAt()))
                break;
            var offer = DriverOffer.builder()
                .requestId(r.getRequestId())
                .tripId(r.getTripId())
                .driverId(d.getId())
                .driverVersionUsed(d.getVersion())
                .distanceKm(d.getDistanceKm())
                .rank(++rank)
                .correlationId(r.getCorrelationId())
                .status(OfferStatus.PENDING)
                .offeredAt(clock.instant())
                .build();
            offer = driverOfferRepository.save(offer);
            ReserveDriverResponse reservation;
            try {
                reservation = driverServiceClient.reserveDriver(d.getId(), r.getTripId(), d.getVersion());
            }
            catch (DriverReservationConflictException ex) {
                offer.setStatus(OfferStatus.CONFLICT);
                offer.setRespondedAt(clock.instant());
                driverOfferRepository.save(offer);
                continue;
            }
            catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
                offer.setStatus(OfferStatus.REJECTED);
                offer.setRespondedAt(clock.instant());
                driverOfferRepository.save(offer);
                continue;
            }
            try {
                if (reservation == null || !"RESERVED".equals(reservation.getStatus())
                        || !d.getId().equals(reservation.getDriverId())
                        || !r.getTripId().equals(reservation.getReservedForTrip())
                        || reservation.getReservedUntil() == null || reservation.getVersion() == null)
                    throw new RestClientException("Invalid driver reservation response");
                if (!clock.instant().isBefore(reservation.getReservedUntil())) {
                    log.info("Expired reservation left to Driver lease cleanup driver={} trip={}", d.getId(), r.getTripId());
                    offer.setStatus(OfferStatus.EXPIRED);
                    offer.setRespondedAt(clock.instant());
                    driverOfferRepository.save(offer);
                    continue;
                }
                if (!"SEARCHING".equals(tripServiceClient.getTripStatus(r.getTripId()))) {
                    log.info("Unneeded reservation left to Driver lease/TripCancelled cleanup driver={} trip={}",
                        d.getId(), r.getTripId());
                    r.setStatus(MatchStatus.EXPIRED);
                    matchRequestRepository.save(r);
                    return response(r);
                }
                offer.setStatus(OfferStatus.ACCEPTED);
                offer.setRespondedAt(clock.instant());
                driverOfferRepository.save(offer);
                r.setMatchedDriverId(d.getId());
                r.setStatus(MatchStatus.MATCHED);
                UUID reservationEventId = UUID.randomUUID();
                r.setActiveReservationEventId(reservationEventId);
                r.setActiveReservationVersion(reservation.getVersion());
                r.setActiveReservedUntil(reservation.getReservedUntil());
                matchRequestRepository.save(r);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tripId", r.getTripId());
                payload.put("passengerId", r.getPassengerId());
                payload.put("driverId", d.getId());
                payload.put("driverName", d.getFullName());
                payload.put("driverLocation", d.getLocation());
                payload.put("estimatedArrivalMin", Math.max(1, (int) Math.ceil(d.getDistanceKm() * 3)));
                payload.put("reservedUntil", reservation.getReservedUntil());
                payload.put("reservationVersion", reservation.getVersion());
                eventPublisher.publish(EventEnvelope.builder()
                    .eventId(reservationEventId)
                    .eventType("DriverReserved")
                    .aggregateId(r.getTripId().toString())
                    .aggregateType("Trip")
                    .producer("matching-service")
                    .correlationId(r.getCorrelationId())
                    .causationId(causationId)
                    .occurredAt(clock.instant())
                    .payload(payload)
                    .build());
                return response(r);
            }
            catch (RuntimeException ex) {
                log.warn("Reservation attempt failed driver={} trip={}; Driver lease will clean up if held",
                    d.getId(), r.getTripId(), ex);
                throw ex;
            }
        }
        r.setStatus(MatchStatus.NO_DRIVER_FOUND);
        matchRequestRepository.save(r);
        return response(r);
    }

}

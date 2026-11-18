package com.example.tripservice.service;

import com.example.tripservice.entity.TripStatus;
import com.example.tripservice.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentTimeoutScheduler {
    private final TripRepository tripRepository;
    private final TripService tripService;

    @Scheduled(fixedDelayString = "${trip.assignment-timeout-check-ms:5000}")
    public void expirePendingAssignments() {
        for (var trip : tripRepository.findByStatus(TripStatus.ASSIGNING)) {
            try {
                tripService.expirePendingAssignment(trip.getTripId());
            }
            catch (RuntimeException ex) {
                log.warn("Assignment expiry deferred for trip={}", trip.getTripId(), ex);
            }
        }
    }
}

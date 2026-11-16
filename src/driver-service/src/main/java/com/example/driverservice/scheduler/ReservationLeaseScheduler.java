package com.example.driverservice.scheduler;

import com.example.driverservice.service.DriverService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Returns drivers whose reservation lease ran out (reserved_until in the past) to AVAILABLE.
 * Switched off with driver.reservation.expiry-check-enabled=false, which the test profile does because its
 * repositories are mocks.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "driver.reservation.expiry-check-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReservationLeaseScheduler {

    private final DriverService driverService;

    @Scheduled(fixedDelayString = "${driver.reservation.expiry-check-ms:5000}")
    public void releaseExpiredReservations() {
        driverService.releaseExpiredReservations();
    }
}

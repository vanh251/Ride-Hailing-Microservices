package com.example.driverservice.scheduler;

import com.example.driverservice.kafka.producer.DriverEventOutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the outbox relay every driver.outbox.poll-interval-ms. Has its own @EnableScheduling because the one on
 * ReservationLeaseScheduler disappears when the lease check is switched off. Switched off with
 * driver.outbox.enabled=false, which the test profile does because its repositories are mocks.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "driver.outbox.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DriverEventOutboxScheduler {

    private final DriverEventOutboxRelay outboxRelay;

    @Scheduled(fixedDelayString = "${driver.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        outboxRelay.publishPending();
    }
}

package com.example.driverservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reservation lease settings bound from the {@code driver.reservation.*} keys in application.yml.
 *
 * @param leaseSeconds how long a RESERVED driver is held before it returns to AVAILABLE on its own
 */
@ConfigurationProperties(prefix = "driver.reservation")
public record ReservationProperties(long leaseSeconds) {
}

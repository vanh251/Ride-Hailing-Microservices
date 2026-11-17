package com.example.driverservice;

import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.exception.ConflictException;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.service.DriverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reservation compare-and-swap against a real database (H2 in PostgreSQL mode), the only driver-service test that
 * is not mocked: the guarantee under test is that the database lets exactly one of several concurrent
 * reservations of the same driver succeed.
 */
@SpringBootTest(properties = {
        // src/test/resources/application.yml excludes the DataSource for the mocked tests; this class needs it back
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:driver_reserve;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DriverReserveConcurrencyTest {

    @Autowired
    private DriverService driverService;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Driver savedDriver(DriverStatus status) {
        return driverRepository.saveAndFlush(Driver.builder()
                .driverId(UUID.randomUUID())
                .fullName("Tran Van Binh")
                .licenseNumber("012345678901")
                .status(status)
                .build());
    }

    private Driver reload(Driver driver) {
        return driverRepository.findById(driver.getDriverId()).orElseThrow();
    }

    /** What happened to one of the competing reservation attempts. */
    private record Attempt(UUID tripId, ReserveDriverResponse response, Throwable failure) {
    }

    // Starts every reservation at the same moment, each for its own trip, all with the version they read beforehand
    private List<Attempt> reserveConcurrently(Driver driver, int requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                UUID tripId = UUID.randomUUID();
                Callable<Attempt> task = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Attempt(tripId, driverService.reserveDriver(driver.getDriverId(), tripId, driver.getVersion()), null);
                    } catch (Throwable failure) {
                        return new Attempt(tripId, null, failure);
                    }
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start");
            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(30, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0} concurrent requests")
    @ValueSource(ints = {2, 20})
    @DisplayName("Concurrent reservations of one driver: exactly one succeeds, every other gets 409 DRIVER_ALREADY_RESERVED")
    void reserveDriver_concurrentRequestsForSameDriver_onlyOneSucceeds(int requests) throws Exception {
        Driver driver = savedDriver(DriverStatus.AVAILABLE);

        List<Attempt> attempts = reserveConcurrently(driver, requests);

        List<Attempt> winners = attempts.stream().filter(attempt -> attempt.response() != null).toList();
        List<Attempt> losers = attempts.stream().filter(attempt -> attempt.failure() != null).toList();
        assertEquals(1, winners.size(), "reservations that succeeded");
        assertEquals(requests - 1, losers.size(), "reservations that were rejected");
        for (Attempt loser : losers) {
            ConflictException conflict = assertInstanceOfConflict(loser.failure());
            assertEquals("DRIVER_ALREADY_RESERVED", conflict.getCode());
        }

        Attempt winner = winners.get(0);
        assertEquals(DriverStatus.RESERVED, winner.response().getStatus());
        assertEquals(winner.tripId(), winner.response().getReservedForTrip());
        assertEquals(driver.getVersion() + 1, winner.response().getVersion());

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(winner.tripId(), stored.getReservedForTrip());
        assertEquals(driver.getVersion() + 1, stored.getVersion(), "version must grow by exactly one");
        assertEquals(winner.response().getReservedUntil(), stored.getReservedUntil());
    }

    private static ConflictException assertInstanceOfConflict(Throwable failure) {
        if (failure instanceof ConflictException conflict) {
            return conflict;
        }
        throw new AssertionError("expected ConflictException but the reservation failed with " + failure, failure);
    }

    @Test
    @DisplayName("A reservation holds the driver for the 30 second lease")
    void reserveDriver_availableDriver_setsThirtySecondLease() {
        Driver driver = savedDriver(DriverStatus.AVAILABLE);
        Instant before = Instant.now();

        ReserveDriverResponse response = driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), driver.getVersion());

        assertNotNull(response.getReservedUntil());
        assertTrue(!response.getReservedUntil().isBefore(before.plusSeconds(29))
                        && !response.getReservedUntil().isAfter(Instant.now().plusSeconds(30)),
                "reservedUntil " + response.getReservedUntil() + " is not 30s after " + before);
    }

    @Test
    @DisplayName("A stale expectedVersion is rejected even when the driver is AVAILABLE")
    void reserveDriver_staleVersion_throwsConflictAndLeavesDriverAvailable() {
        Driver driver = savedDriver(DriverStatus.AVAILABLE);

        assertThrows(ConflictException.class,
                () -> driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), driver.getVersion() + 1));

        Driver stored = reload(driver);
        assertEquals(DriverStatus.AVAILABLE, stored.getStatus());
        assertNull(stored.getReservedForTrip());
        assertEquals(driver.getVersion(), stored.getVersion());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OFFLINE", "RESERVED", "BUSY"})
    @DisplayName("Only an AVAILABLE driver can be reserved")
    void reserveDriver_driverNotAvailable_throwsConflict(DriverStatus status) {
        Driver driver = savedDriver(status);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), driver.getVersion()));

        assertEquals("DRIVER_ALREADY_RESERVED", conflict.getCode());
        assertEquals(status, reload(driver).getStatus());
    }

    @Test
    @DisplayName("Releasing returns the driver to AVAILABLE; it can be reserved again with the new version only")
    void releaseReservation_reservedDriver_canBeReservedAgainWithNewVersion() {
        Driver driver = savedDriver(DriverStatus.AVAILABLE);
        int version = driver.getVersion();
        driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), version);

        driverService.releaseReservation(driver.getDriverId());

        Driver released = reload(driver);
        assertEquals(DriverStatus.AVAILABLE, released.getStatus());
        assertNull(released.getReservedForTrip());
        assertNull(released.getReservedUntil());
        assertEquals(version + 2, released.getVersion());

        UUID nextTrip = UUID.randomUUID();
        assertThrows(ConflictException.class, () -> driverService.reserveDriver(driver.getDriverId(), nextTrip, version));
        assertEquals(version + 3, driverService.reserveDriver(driver.getDriverId(), nextTrip, version + 2).getVersion());
    }

    @Test
    @DisplayName("Releasing a driver that is not RESERVED changes nothing")
    void releaseReservation_busyDriver_leavesDriverUnchanged() {
        Driver driver = savedDriver(DriverStatus.BUSY);

        driverService.releaseReservation(driver.getDriverId());

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(driver.getVersion(), stored.getVersion());
    }

    @Test
    @DisplayName("Lease expiry releases only drivers whose reserved_until has passed")
    void releaseExpiredReservations_expiredAndActiveLeases_releasesOnlyExpired() {
        Driver expired = savedDriver(DriverStatus.AVAILABLE);
        Driver active = savedDriver(DriverStatus.AVAILABLE);
        Instant now = Instant.now();
        // Reserved 31 seconds ago, so the 30 second lease ended a second ago
        transactionTemplate.executeWithoutResult(status -> driverRepository.reserve(
                expired.getDriverId(), UUID.randomUUID(), expired.getVersion(), now.minusSeconds(1), now.minusSeconds(31)));
        UUID activeTrip = UUID.randomUUID();
        driverService.reserveDriver(active.getDriverId(), activeTrip, active.getVersion());

        int released = driverService.releaseExpiredReservations();

        assertEquals(1, released);
        Driver expiredStored = reload(expired);
        assertEquals(DriverStatus.AVAILABLE, expiredStored.getStatus());
        assertNull(expiredStored.getReservedForTrip());
        assertNull(expiredStored.getReservedUntil());
        assertEquals(expired.getVersion() + 2, expiredStored.getVersion());
        Driver activeStored = reload(active);
        assertEquals(DriverStatus.RESERVED, activeStored.getStatus());
        assertEquals(activeTrip, activeStored.getReservedForTrip());
    }
}

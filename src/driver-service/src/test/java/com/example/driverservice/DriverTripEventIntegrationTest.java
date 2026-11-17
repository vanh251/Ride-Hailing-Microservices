package com.example.driverservice;

import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import com.example.driverservice.service.DriverService;
import com.example.driverservice.service.DriverTripEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver status changes driven by trip events (docs/events.md §4.3, §4.5, §4.8) against a real database
 * (H2 in PostgreSQL mode): the conditions live in the SQL WHERE clauses, so mocks cannot check them.
 */
@SpringBootTest(properties = {
        // src/test/resources/application.yml excludes the DataSource for the mocked tests; this class needs it back
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:driver_trip_events;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DriverTripEventIntegrationTest {

    @Autowired
    private DriverTripEventService driverTripEventService;

    @Autowired
    private DriverService driverService;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Driver savedDriver() {
        return driverRepository.saveAndFlush(Driver.builder()
                .driverId(UUID.randomUUID())
                .fullName("Tran Van Binh")
                .licenseNumber("012345678901")
                .status(DriverStatus.AVAILABLE)
                .build());
    }

    private Driver reload(Driver driver) {
        return driverRepository.findById(driver.getDriverId()).orElseThrow();
    }

    /** An AVAILABLE driver reserved for a new trip. */
    private Driver reservedDriver(UUID tripId) {
        Driver driver = savedDriver();
        driverService.reserveDriver(driver.getDriverId(), tripId, driver.getVersion());
        return driver;
    }

    /** A driver reserved for the trip and then assigned to it. */
    private Driver busyDriver(UUID tripId) {
        Driver driver = reservedDriver(tripId);
        driverTripEventService.onDriverAssigned(UUID.randomUUID(), tripId, driver.getDriverId());
        return driver;
    }

    private static void assertReleased(Driver stored) {
        assertEquals(DriverStatus.AVAILABLE, stored.getStatus());
        assertNull(stored.getReservedForTrip());
        assertNull(stored.getReservedUntil());
    }

    @Test
    @DisplayName("DriverAssigned: RESERVED -> BUSY, lease cleared, trip kept, version + 1")
    void onDriverAssigned_driverReservedForTrip_becomesBusy() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        driverTripEventService.onDriverAssigned(eventId, tripId, driver.getDriverId());

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
        assertNull(stored.getReservedUntil());
        assertEquals(reservedVersion + 1, stored.getVersion());
        assertEquals("DriverAssigned", processedEventRepository.findById(eventId).orElseThrow().getEventType());
        assertNotNull(processedEventRepository.findById(eventId).orElseThrow().getProcessedAt());
    }

    @Test
    @DisplayName("An assigned driver is not returned to AVAILABLE when its reservation lease runs out")
    void releaseExpiredReservations_driverAlreadyAssigned_staysBusy() {
        Driver driver = savedDriver();
        UUID tripId = UUID.randomUUID();
        Instant now = Instant.now();
        // Reserved 31 seconds ago, so the 30 second lease ended a second ago
        transactionTemplate.executeWithoutResult(status -> driverRepository.reserve(
                driver.getDriverId(), tripId, driver.getVersion(), now.minusSeconds(1), now.minusSeconds(31)));
        driverTripEventService.onDriverAssigned(UUID.randomUUID(), tripId, driver.getDriverId());

        driverService.releaseExpiredReservations();

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
    }

    @Test
    @DisplayName("DriverAssigned for a trip the driver is not reserved for changes nothing but is recorded")
    void onDriverAssigned_driverReservedForAnotherTrip_leavesDriverUnchanged() {
        UUID reservedTrip = UUID.randomUUID();
        Driver driver = reservedDriver(reservedTrip);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        driverTripEventService.onDriverAssigned(eventId, UUID.randomUUID(), driver.getDriverId());

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(reservedTrip, stored.getReservedForTrip());
        assertNotNull(stored.getReservedUntil());
        assertEquals(reservedVersion, stored.getVersion());
        assertTrue(processedEventRepository.existsById(eventId));
    }

    @Test
    @DisplayName("DriverAssigned after the lease expired finds an AVAILABLE driver and leaves it alone")
    void onDriverAssigned_reservationAlreadyExpired_leavesDriverAvailable() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        driverService.releaseReservation(driver.getDriverId(), tripId);

        driverTripEventService.onDriverAssigned(UUID.randomUUID(), tripId, driver.getDriverId());

        assertReleased(reload(driver));
    }

    @Test
    @DisplayName("TripCompleted: BUSY -> AVAILABLE, reservation cleared, version + 1")
    void onTripCompleted_busyDriverOfTrip_becomesAvailable() {
        UUID tripId = UUID.randomUUID();
        Driver driver = busyDriver(tripId);
        int busyVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        driverTripEventService.onTripCompleted(eventId, tripId);

        Driver stored = reload(driver);
        assertReleased(stored);
        assertEquals(busyVersion + 1, stored.getVersion());
        assertEquals("TripCompleted", processedEventRepository.findById(eventId).orElseThrow().getEventType());
    }

    @Test
    @DisplayName("TripCompleted does not release a driver that is only RESERVED")
    void onTripCompleted_driverOnlyReserved_staysReserved() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);

        driverTripEventService.onTripCompleted(UUID.randomUUID(), tripId);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
    }

    @ParameterizedTest(name = "driver is {0}")
    @ValueSource(strings = {"RESERVED", "BUSY"})
    @DisplayName("TripCancelled: RESERVED or BUSY -> AVAILABLE, reservation cleared, version + 1")
    void onTripCancelled_driverOfTrip_becomesAvailable(DriverStatus status) {
        UUID tripId = UUID.randomUUID();
        Driver driver = status == DriverStatus.BUSY ? busyDriver(tripId) : reservedDriver(tripId);
        int versionBefore = reload(driver).getVersion();
        assertEquals(status, reload(driver).getStatus());

        driverTripEventService.onTripCancelled(UUID.randomUUID(), tripId);

        Driver stored = reload(driver);
        assertReleased(stored);
        assertEquals(versionBefore + 1, stored.getVersion());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TripCompleted", "TripCancelled"})
    @DisplayName("An event of another trip does not release the driver, and is still recorded")
    void onRelease_driverBusyWithAnotherTrip_staysBusy(String eventType) {
        UUID currentTrip = UUID.randomUUID();
        Driver driver = busyDriver(currentTrip);
        int busyVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();
        UUID otherTrip = UUID.randomUUID();

        if ("TripCompleted".equals(eventType)) {
            driverTripEventService.onTripCompleted(eventId, otherTrip);
        } else {
            driverTripEventService.onTripCancelled(eventId, otherTrip);
        }

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(currentTrip, stored.getReservedForTrip());
        assertEquals(busyVersion, stored.getVersion());
        assertEquals(eventType, processedEventRepository.findById(eventId).orElseThrow().getEventType());
    }

    @Test
    @DisplayName("TripCancelled before any driver was reserved is recorded and changes no driver")
    void onTripCancelled_noDriverForTrip_recordsEventOnly() {
        Driver bystander = savedDriver();
        UUID eventId = UUID.randomUUID();

        driverTripEventService.onTripCancelled(eventId, UUID.randomUUID());

        assertEquals(DriverStatus.AVAILABLE, reload(bystander).getStatus());
        assertEquals(bystander.getVersion(), reload(bystander).getVersion());
        assertTrue(processedEventRepository.existsById(eventId));
    }

    @Test
    @DisplayName("The same eventId delivered twice takes effect once")
    void onTripCancelled_sameEventDeliveredAgain_secondDeliveryIsSkipped() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        UUID eventId = UUID.randomUUID();
        driverTripEventService.onTripCancelled(eventId, tripId);
        Driver released = reload(driver);
        assertReleased(released);
        // The driver is matched to the same trip again; a redelivery of the old cancellation must not undo that
        driverService.reserveDriver(driver.getDriverId(), tripId, released.getVersion());

        driverTripEventService.onTripCancelled(eventId, tripId);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
        assertEquals(released.getVersion() + 1, stored.getVersion());
    }

    @Test
    @DisplayName("Both compensation paths of a cancellation can run: the second finds nothing to release")
    void onTripCancelled_afterDeleteReserve_changesNothing() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        driverService.releaseReservation(driver.getDriverId(), tripId);
        int releasedVersion = reload(driver).getVersion();

        driverTripEventService.onTripCancelled(UUID.randomUUID(), tripId);
        driverService.releaseReservation(driver.getDriverId(), tripId);

        Driver stored = reload(driver);
        assertReleased(stored);
        assertEquals(releasedVersion, stored.getVersion());
    }

    @Test
    @DisplayName("Full ride: AVAILABLE -> RESERVED -> BUSY -> AVAILABLE, then the driver can be reserved again")
    void tripLifecycle_reserveAssignComplete_driverIsAvailableAgain() {
        UUID tripId = UUID.randomUUID();
        Driver driver = savedDriver();
        int version = driver.getVersion();

        driverService.reserveDriver(driver.getDriverId(), tripId, version);
        driverTripEventService.onDriverAssigned(UUID.randomUUID(), tripId, driver.getDriverId());
        driverTripEventService.onTripCompleted(UUID.randomUUID(), tripId);

        Driver stored = reload(driver);
        assertReleased(stored);
        assertEquals(version + 3, stored.getVersion());
        assertFalse(driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), stored.getVersion())
                .getReservedUntil().isBefore(Instant.now()));
    }
}

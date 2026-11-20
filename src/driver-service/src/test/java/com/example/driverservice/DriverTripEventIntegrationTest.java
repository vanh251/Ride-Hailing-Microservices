package com.example.driverservice;

import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.OutboxEvent;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.OutboxEventRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver status changes driven by trip events (docs/events.md §4.3, §4.5, §4.8) against a real database
 * (H2 in PostgreSQL mode): the conditions live in the SQL WHERE clauses, so mocks cannot check them.
 * The outbox relay is off in the test profile, so the DriverAssigned results stay in outbox_events to be read.
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

    private static final String CORRELATION_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final UUID PASSENGER_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

    /** DriverAssigned as trip-service sends it, for the given reservation version. */
    private void assign(UUID eventId, UUID tripId, Driver driver, int reservationVersion) {
        driverTripEventService.onDriverAssigned(eventId, CORRELATION_ID, tripId, driver.getDriverId(), PASSENGER_ID,
                reservationVersion);
    }

    /** A driver reserved for the trip and then assigned to it. */
    private Driver busyDriver(UUID tripId) {
        Driver driver = reservedDriver(tripId);
        assign(UUID.randomUUID(), tripId, driver, reload(driver).getVersion());
        return driver;
    }

    /** The results queued in the outbox for this trip. */
    private List<OutboxEvent> results(UUID tripId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> tripId.toString().equals(event.getAggregateId()))
                .toList();
    }

    /** The only result queued for this trip, after checking its type and the command it answers. */
    private JsonNode onlyResult(UUID tripId, String eventType, UUID driverAssignedEventId, int reservationVersion) {
        List<OutboxEvent> results = results(tripId);
        assertEquals(1, results.size(), "results queued for the trip");
        assertEquals(eventType, results.get(0).getEventType());
        JsonNode envelope = objectMapper.readTree(results.get(0).getPayload());
        assertEquals(eventType, envelope.path("eventType").asText());
        assertEquals(driverAssignedEventId.toString(), envelope.path("causationId").asText());
        assertEquals(CORRELATION_ID, envelope.path("correlationId").asText());
        assertEquals(driverAssignedEventId.toString(), envelope.path("payload").path("driverAssignedEventId").asText());
        assertEquals(reservationVersion, envelope.path("payload").path("reservationVersion").intValue());
        assertEquals(PASSENGER_ID.toString(), envelope.path("payload").path("passengerId").asText());
        return envelope;
    }

    private static void assertReleased(Driver stored) {
        assertEquals(DriverStatus.AVAILABLE, stored.getStatus());
        assertNull(stored.getReservedForTrip());
        assertNull(stored.getReservedUntil());
    }

    @Test
    @DisplayName("DriverAssigned for the live reservation: RESERVED -> BUSY, lease cleared, version + 1, one confirmation queued")
    void onDriverAssigned_driverReservedForTrip_becomesBusyAndQueuesConfirmation() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        assign(eventId, tripId, driver, reservedVersion);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
        assertNull(stored.getReservedUntil());
        assertEquals(reservedVersion + 1, stored.getVersion());
        assertEquals("DriverAssigned", processedEventRepository.findById(eventId).orElseThrow().getEventType());
        assertNotNull(processedEventRepository.findById(eventId).orElseThrow().getProcessedAt());
        JsonNode envelope = onlyResult(tripId, "DriverAssignmentConfirmed", eventId, reservedVersion);
        assertEquals(driver.getDriverId().toString(), envelope.path("payload").path("driverId").asText());
    }

    @Test
    @DisplayName("An assigned driver is not returned to AVAILABLE when the lease job runs after the lease time")
    void releaseExpiredReservations_driverAlreadyAssigned_staysBusy() {
        UUID tripId = UUID.randomUUID();
        Driver driver = busyDriver(tripId);

        // As if the lease job ran a minute later, after the 30 second lease would have ended
        transactionTemplate.executeWithoutResult(status ->
                driverRepository.releaseExpiredReservations(Instant.now().plusSeconds(60)));

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(tripId, stored.getReservedForTrip());
    }

    @Test
    @DisplayName("DriverAssigned for a trip the driver is not reserved for is rejected and changes nothing")
    void onDriverAssigned_driverReservedForAnotherTrip_rejectsAndLeavesDriverUnchanged() {
        UUID reservedTrip = UUID.randomUUID();
        Driver driver = reservedDriver(reservedTrip);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();
        UUID otherTrip = UUID.randomUUID();

        assign(eventId, otherTrip, driver, reservedVersion);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(reservedTrip, stored.getReservedForTrip());
        assertNotNull(stored.getReservedUntil());
        assertEquals(reservedVersion, stored.getVersion());
        assertTrue(processedEventRepository.existsById(eventId));
        onlyResult(otherTrip, "DriverAssignmentRejected", eventId, reservedVersion);
        assertTrue(results(reservedTrip).isEmpty());
    }

    @Test
    @DisplayName("DriverAssigned with another reservation version is rejected and the reservation stays as it is")
    void onDriverAssigned_wrongReservationVersion_rejectsAndLeavesDriverReserved() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        assign(eventId, tripId, driver, reservedVersion + 1);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(reservedVersion, stored.getVersion());
        assertNotNull(stored.getReservedUntil());
        onlyResult(tripId, "DriverAssignmentRejected", eventId, reservedVersion + 1);
    }

    @Test
    @DisplayName("DriverAssigned after the lease ended, before the lease job released the driver, is rejected")
    void onDriverAssigned_leaseEndedButNotYetReleased_rejectsAndLeavesDriverReserved() {
        Driver driver = savedDriver();
        UUID tripId = UUID.randomUUID();
        Instant now = Instant.now();
        // Reserved 31 seconds ago, so the 30 second lease ended a second ago; the lease job has not run yet
        transactionTemplate.executeWithoutResult(status -> driverRepository.reserve(
                driver.getDriverId(), tripId, driver.getVersion(), now.minusSeconds(1), now.minusSeconds(31)));
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        assign(eventId, tripId, driver, reservedVersion);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.RESERVED, stored.getStatus());
        assertEquals(reservedVersion, stored.getVersion());
        onlyResult(tripId, "DriverAssignmentRejected", eventId, reservedVersion);
    }

    @Test
    @DisplayName("DriverAssigned after the reservation was released finds an AVAILABLE driver, leaves it alone and is rejected")
    void onDriverAssigned_reservationAlreadyReleased_rejectsAndLeavesDriverAvailable() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int reservedVersion = reload(driver).getVersion();
        driverService.releaseReservation(driver.getDriverId(), tripId);
        UUID eventId = UUID.randomUUID();

        assign(eventId, tripId, driver, reservedVersion);

        assertReleased(reload(driver));
        onlyResult(tripId, "DriverAssignmentRejected", eventId, reservedVersion);
    }

    @Test
    @DisplayName("A late DriverAssigned of an earlier attempt cannot take over the newer reservation of the same trip")
    void onDriverAssigned_earlierAttemptOfSameTrip_doesNotTakeOverNewerReservation() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int firstAttemptVersion = reload(driver).getVersion();
        // The first reservation is released and the same driver is reserved for the same trip again
        driverService.releaseReservation(driver.getDriverId(), tripId);
        driverService.reserveDriver(driver.getDriverId(), tripId, reload(driver).getVersion());
        int secondAttemptVersion = reload(driver).getVersion();
        UUID lateEventId = UUID.randomUUID();
        UUID currentEventId = UUID.randomUUID();

        assign(lateEventId, tripId, driver, firstAttemptVersion);

        assertEquals(DriverStatus.RESERVED, reload(driver).getStatus());
        assertEquals(secondAttemptVersion, reload(driver).getVersion());

        assign(currentEventId, tripId, driver, secondAttemptVersion);

        assertEquals(DriverStatus.BUSY, reload(driver).getStatus());
        List<String> resultTypes = results(tripId).stream().map(OutboxEvent::getEventType).sorted().toList();
        assertEquals(List.of("DriverAssignmentConfirmed", "DriverAssignmentRejected"), resultTypes);
    }

    @Test
    @DisplayName("The same DriverAssigned delivered twice changes the version once and queues one result")
    void onDriverAssigned_sameEventDeliveredAgain_takesEffectOnce() {
        UUID tripId = UUID.randomUUID();
        Driver driver = reservedDriver(tripId);
        int reservedVersion = reload(driver).getVersion();
        UUID eventId = UUID.randomUUID();

        assign(eventId, tripId, driver, reservedVersion);
        assign(eventId, tripId, driver, reservedVersion);

        Driver stored = reload(driver);
        assertEquals(DriverStatus.BUSY, stored.getStatus());
        assertEquals(reservedVersion + 1, stored.getVersion());
        onlyResult(tripId, "DriverAssignmentConfirmed", eventId, reservedVersion);
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
        assign(UUID.randomUUID(), tripId, driver, version + 1);
        driverTripEventService.onTripCompleted(UUID.randomUUID(), tripId);

        Driver stored = reload(driver);
        assertReleased(stored);
        assertEquals(version + 3, stored.getVersion());
        assertFalse(driverService.reserveDriver(driver.getDriverId(), UUID.randomUUID(), stored.getVersion())
                .getReservedUntil().isBefore(Instant.now()));
    }
}

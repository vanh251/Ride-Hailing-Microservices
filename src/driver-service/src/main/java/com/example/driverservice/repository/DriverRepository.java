package com.example.driverservice.repository;

import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DriverRepository extends JpaRepository<Driver, UUID> {

    // BETWEEN never matches NULL, so drivers that never reported a position are left out
    List<Driver> findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
            DriverStatus status, BigDecimal minLat, BigDecimal maxLat, BigDecimal minLng, BigDecimal maxLng);

    /**
     * Stores a position report. A bulk JPQL update does not touch @Version on purpose: version guards status changes
     * (the reservation CAS), and frequent heartbeats must not make a reservation fail. Returns the rows updated.
     */
    @Modifying
    @Query("UPDATE Driver d SET d.currentLat = :lat, d.currentLng = :lng, d.lastHeartbeatAt = :now, d.updatedAt = :now "
            + "WHERE d.driverId = :driverId")
    int updateLocation(@Param("driverId") UUID driverId, @Param("lat") BigDecimal lat,
                       @Param("lng") BigDecimal lng, @Param("now") Instant now);

    /**
     * Compare-and-swap reservation (docs/database_schema.md): one statement, so of several concurrent requests for
     * the same driver only one can match the WHERE clause. Returns 1 when the driver was reserved, 0 when it is not
     * AVAILABLE, its version is not expectedVersion, or it does not exist.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Driver d SET d.status = com.example.driverservice.entity.DriverStatus.RESERVED, "
            + "d.reservedForTrip = :tripId, d.reservedUntil = :reservedUntil, d.version = d.version + 1, d.updatedAt = :now "
            + "WHERE d.driverId = :driverId AND d.status = com.example.driverservice.entity.DriverStatus.AVAILABLE "
            + "AND d.version = :expectedVersion")
    int reserve(@Param("driverId") UUID driverId, @Param("tripId") UUID tripId,
                @Param("expectedVersion") int expectedVersion, @Param("reservedUntil") Instant reservedUntil,
                @Param("now") Instant now);

    /** RESERVED -> AVAILABLE only for the owning trip. Returns 0 for a different trip or status. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Driver d SET d.status = com.example.driverservice.entity.DriverStatus.AVAILABLE, "
            + "d.reservedForTrip = null, d.reservedUntil = null, d.version = d.version + 1, d.updatedAt = :now "
            + "WHERE d.driverId = :driverId AND d.status = com.example.driverservice.entity.DriverStatus.RESERVED "
            + "AND d.reservedForTrip = :tripId")
    int releaseReservation(@Param("driverId") UUID driverId, @Param("tripId") UUID tripId,
                           @Param("now") Instant now);

    /** RESERVED -> AVAILABLE for every driver whose lease ended before {@code now}. Returns the drivers released. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Driver d SET d.status = com.example.driverservice.entity.DriverStatus.AVAILABLE, "
            + "d.reservedForTrip = null, d.reservedUntil = null, d.version = d.version + 1, d.updatedAt = :now "
            + "WHERE d.status = com.example.driverservice.entity.DriverStatus.RESERVED AND d.reservedUntil < :now")
    int releaseExpiredReservations(@Param("now") Instant now);

    /**
     * RESERVED -> BUSY when the trip the driver is reserved for is assigned to it (event DriverAssigned,
     * docs/events.md §4.3). One statement that only matches the exact reservation: this trip, the version the
     * reservation produced (reservationVersion) and a lease that is still running, so a late DriverAssigned of an
     * earlier attempt cannot take over a newer reservation. The lease no longer applies, so reservedUntil is
     * cleared; reservedForTrip stays, it says which trip the driver is on. Returns 1 when the driver became BUSY,
     * 0 when the reservation does not match.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Driver d SET d.status = com.example.driverservice.entity.DriverStatus.BUSY, "
            + "d.reservedUntil = null, d.version = d.version + 1, d.updatedAt = :now "
            + "WHERE d.driverId = :driverId AND d.status = com.example.driverservice.entity.DriverStatus.RESERVED "
            + "AND d.reservedForTrip = :tripId AND d.version = :reservationVersion AND d.reservedUntil > :now")
    int markBusy(@Param("driverId") UUID driverId, @Param("tripId") UUID tripId,
                 @Param("reservationVersion") int reservationVersion, @Param("now") Instant now);

    /**
     * Back to AVAILABLE for the driver held by this trip, if it is in one of the given statuses (events
     * TripCompleted and TripCancelled). Matching on reservedForTrip keeps a late event of an old trip from
     * releasing a driver that is already on another one. Returns 0 when no driver is held by the trip.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Driver d SET d.status = com.example.driverservice.entity.DriverStatus.AVAILABLE, "
            + "d.reservedForTrip = null, d.reservedUntil = null, d.version = d.version + 1, d.updatedAt = :now "
            + "WHERE d.reservedForTrip = :tripId AND d.status IN :statuses")
    int releaseForTrip(@Param("tripId") UUID tripId, @Param("statuses") Collection<DriverStatus> statuses,
                       @Param("now") Instant now);
}

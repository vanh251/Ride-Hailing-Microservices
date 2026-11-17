package com.example.driverservice.service;

import com.example.driverservice.dto.request.CreateDriverRequest;
import com.example.driverservice.dto.response.AvailableDriverResponse;
import com.example.driverservice.dto.response.DriverDetailResponse;
import com.example.driverservice.dto.response.DriverLocationResponse;
import com.example.driverservice.dto.response.DriverProfileResponse;
import com.example.driverservice.dto.response.DriverStatusResponse;
import com.example.driverservice.dto.response.NearbyDriverResponse;
import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.entity.DriverStatus;

import java.util.List;
import java.util.UUID;

public interface DriverService {

    DriverProfileResponse createDriver(CreateDriverRequest request);

    void deleteDriverByUserId(UUID userId);

    /**
     * AVAILABLE drivers within radiusKm of the point, with the version used for reservation: page {@code page}
     * (from 0) of {@code size} drivers, ordered by distance, nearest first unless {@code farthestFirst}.
     */
    List<AvailableDriverResponse> findAvailableDrivers(double latitude, double longitude, double radiusKm,
                                                       int page, int size, boolean farthestFirst);

    /** AVAILABLE drivers within radiusKm of the pickup point, paged and ordered like {@link #findAvailableDrivers}. */
    List<NearbyDriverResponse> findNearbyDrivers(double latitude, double longitude, double radiusKm,
                                                 int page, int size, boolean farthestFirst);

    /** Driver-initiated OFFLINE ⇄ AVAILABLE switch; callerId is the user id from the JWT. */
    DriverStatusResponse updateStatus(UUID driverId, DriverStatus target, UUID callerId);

    /** Stores the driver's own position report; callerId is the user id from the JWT. */
    DriverLocationResponse updateLocation(UUID driverId, double latitude, double longitude, UUID callerId);

    /** Profile, active vehicle and position; an OFFLINE driver's position is shown only to the driver (callerId). */
    DriverDetailResponse getDriver(UUID driverId, UUID callerId);

    /**
     * AVAILABLE -> RESERVED for tripId, held for the lease. Fails with 409 DRIVER_ALREADY_RESERVED when the driver
     * is not AVAILABLE or its version is no longer expectedVersion.
     */
    ReserveDriverResponse reserveDriver(UUID driverId, UUID tripId, int expectedVersion);

    /** RESERVED -> AVAILABLE only for tripId (compensation). A late release for another trip does nothing. */
    void releaseReservation(UUID driverId, UUID tripId);

    /** RESERVED -> AVAILABLE for every driver whose lease has run out; returns how many were released. */
    int releaseExpiredReservations();
}

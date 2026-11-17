package com.example.driverservice.service;

import com.example.driverservice.config.ReservationProperties;
import com.example.driverservice.dto.request.CreateDriverRequest;
import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.dto.request.VehicleRequest;
import com.example.driverservice.dto.response.AvailableDriverResponse;
import com.example.driverservice.dto.response.DriverDetailResponse;
import com.example.driverservice.dto.response.DriverLocationResponse;
import com.example.driverservice.dto.response.NearbyDriverResponse;
import com.example.driverservice.dto.response.DriverProfileResponse;
import com.example.driverservice.dto.response.DriverStatusResponse;
import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.Vehicle;
import com.example.driverservice.entity.VehicleType;
import com.example.driverservice.exception.BusinessException;
import com.example.driverservice.exception.ConflictException;
import com.example.driverservice.mapper.DriverMapper;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PASSENGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private DriverRepository driverRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Spy
    private DriverMapper driverMapper = new DriverMapper();

    @Spy
    private ReservationProperties reservationProperties = new ReservationProperties(30);

    @InjectMocks
    private DriverServiceImpl driverService;

    private CreateDriverRequest request(String licensePlate, int year) {
        return CreateDriverRequest.builder()
                .userId(USER_ID)
                .fullName(" Tran Van B ")
                .licenseNumber("012345678901")
                .vehicle(VehicleRequest.builder()
                        .licensePlate(licensePlate)
                        .make(" Toyota ")
                        .model("Vios")
                        .color("White")
                        .year(year)
                        .vehicleType(VehicleType.SEDAN)
                        .build())
                .build();
    }

    /** Simulates the database assigning ids and timestamps on insert. */
    private void stubSavesAssignIds() {
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> {
            Driver driver = invocation.getArgument(0);
            driver.setCreatedAt(Instant.now());
            return driver;
        });
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenAnswer(invocation -> {
            Vehicle vehicle = invocation.getArgument(0);
            vehicle.setVehicleId(UUID.randomUUID());
            return vehicle;
        });
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    @Test
    @DisplayName("createDriver saves an OFFLINE driver whose id is the user id, with the plate normalized")
    void createDriver_validRequest_savesOfflineDriverAndVehicle() {
        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(false);
        stubSavesAssignIds();

        DriverProfileResponse response = driverService.createDriver(request("29a-123.45", 2022));

        ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository).saveAndFlush(vehicleCaptor.capture());
        Vehicle saved = vehicleCaptor.getValue();
        assertEquals("29A-12345", saved.getLicensePlate());
        assertEquals("Toyota", saved.getMake());
        assertEquals((short) 2022, saved.getYear());
        assertEquals(USER_ID, saved.getDriver().getDriverId());
        assertEquals("Tran Van B", saved.getDriver().getFullName());

        assertEquals(USER_ID, response.getDriverId());
        assertEquals("Tran Van B", response.getFullName());
        assertEquals(DriverStatus.OFFLINE, response.getStatus());
        assertEquals("012345678901", response.getLicenseNumber());
        assertEquals("29A-12345", response.getVehicle().getLicensePlate());
        assertEquals(VehicleType.SEDAN, response.getVehicle().getVehicleType());
        assertNotNull(response.getVehicle().getVehicleId());
    }

    @Test
    @DisplayName("createDriver for a user that already has a profile throws 409 DRIVER_PROFILE_EXISTS without saving")
    void createDriver_userAlreadyHasProfile_throwsConflict() {
        when(driverRepository.existsById(USER_ID)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> driverService.createDriver(request("29A-12345", 2022)));

        assertEquals("DRIVER_PROFILE_EXISTS", ex.getCode());
        verify(driverRepository, never()).saveAndFlush(any());
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createDriver with a plate already registered (in another notation) throws 409 LICENSE_PLATE_ALREADY_REGISTERED")
    void createDriver_plateAlreadyRegistered_throwsConflict() {
        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> driverService.createDriver(request("29A-123.45", 2022)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("LICENSE_PLATE_ALREADY_REGISTERED", ex.getCode());
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createDriver that loses a race on the plate unique index throws 409 instead of 500")
    void createDriver_concurrentPlateInsert_throwsConflict() {
        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenThrow(uniqueViolation(Vehicle.UQ_LICENSE_PLATE));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> driverService.createDriver(request("29A-12345", 2022)));

        assertEquals("LICENSE_PLATE_ALREADY_REGISTERED", ex.getCode());
    }

    @Test
    @DisplayName("createDriver that loses a race on the driver_id primary key throws 409 DRIVER_PROFILE_EXISTS")
    void createDriver_concurrentUserInsert_throwsConflict() {
        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class))).thenThrow(uniqueViolation(Driver.PRIMARY_KEY));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> driverService.createDriver(request("29A-12345", 2022)));

        assertEquals("DRIVER_PROFILE_EXISTS", ex.getCode());
    }

    @Test
    @DisplayName("createDriver rethrows an integrity violation it does not recognise")
    void createDriver_otherIntegrityViolation_rethrows() {
        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class))).thenThrow(uniqueViolation("some_other_constraint"));

        assertThrows(DataIntegrityViolationException.class, () -> driverService.createDriver(request("29A-12345", 2022)));
    }

    @Test
    @DisplayName("createDriver accepts next year's model and rejects anything later with 400")
    void createDriver_yearAfterNextYear_throwsValidationError() {
        int nextYear = Year.now(ZoneOffset.UTC).getValue() + 1;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.createDriver(request("29A-12345", nextYear + 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verifyNoInteractions(driverRepository, vehicleRepository);

        when(driverRepository.existsById(USER_ID)).thenReturn(false);
        when(vehicleRepository.existsByLicensePlate("29A-12345")).thenReturn(false);
        stubSavesAssignIds();
        assertEquals(nextYear, driverService.createDriver(request("29A-12345", nextYear)).getVehicle().getYear());
    }

    @Test
    @DisplayName("deleteDriverByUserId without a profile does nothing (idempotent compensation)")
    void deleteDriverByUserId_noProfile_doesNothing() {
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> driverService.deleteDriverByUserId(USER_ID));

        verify(driverRepository, never()).delete(any());
        verifyNoInteractions(vehicleRepository);
    }

    @Test
    @DisplayName("deleteDriverByUserId removes the vehicles first, then the OFFLINE driver")
    void deleteDriverByUserId_offlineProfile_deletesVehiclesThenDriver() {
        Driver driver = Driver.builder().driverId(USER_ID).status(DriverStatus.OFFLINE).build();
        List<Vehicle> vehicles = List.of(Vehicle.builder().driver(driver).licensePlate("29A-12345").build());
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriver(driver)).thenReturn(vehicles);

        driverService.deleteDriverByUserId(USER_ID);

        InOrder inOrder = inOrder(vehicleRepository, driverRepository);
        inOrder.verify(vehicleRepository).deleteAll(vehicles);
        inOrder.verify(driverRepository).delete(driver);
    }

    @Test
    @DisplayName("deleteDriverByUserId refuses to delete a driver that is not OFFLINE")
    void deleteDriverByUserId_availableDriver_throwsConflict() {
        Driver driver = Driver.builder().driverId(USER_ID).status(DriverStatus.AVAILABLE).build();
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driver));

        ConflictException ex = assertThrows(ConflictException.class, () -> driverService.deleteDriverByUserId(USER_ID));

        assertEquals("DRIVER_PROFILE_IN_USE", ex.getCode());
        verify(driverRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteDriverByUserId refuses to delete a driver reserved for a trip")
    void deleteDriverByUserId_reservedDriver_throwsConflict() {
        Driver driver = Driver.builder().driverId(USER_ID).status(DriverStatus.OFFLINE).reservedForTrip(UUID.randomUUID()).build();
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driver));

        assertThrows(ConflictException.class, () -> driverService.deleteDriverByUserId(USER_ID));

        verify(driverRepository, never()).delete(any());
    }

    // ---- POST / DELETE /api/drivers/{id}/reserve ----

    @Test
    @DisplayName("reserveDriver returns RESERVED, the trip, version + 1 and a 30 second lease when the CAS updates one row")
    void reserveDriver_casUpdatesOneRow_returnsReservation() {
        UUID tripId = UUID.randomUUID();
        when(driverRepository.reserve(eq(USER_ID), eq(tripId), eq(5), any(Instant.class), any(Instant.class))).thenReturn(1);

        ReserveDriverResponse response = driverService.reserveDriver(USER_ID, tripId, 5);

        assertEquals(USER_ID, response.getDriverId());
        assertEquals(DriverStatus.RESERVED, response.getStatus());
        assertEquals(tripId, response.getReservedForTrip());
        assertEquals(6, response.getVersion());
        ArgumentCaptor<Instant> reservedUntil = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(driverRepository).reserve(eq(USER_ID), eq(tripId), eq(5), reservedUntil.capture(), now.capture());
        assertEquals(now.getValue().plusSeconds(30), reservedUntil.getValue());
        assertEquals(reservedUntil.getValue(), response.getReservedUntil());
    }

    @Test
    @DisplayName("reserveDriver throws 409 DRIVER_ALREADY_RESERVED when the CAS updates no row of an existing driver")
    void reserveDriver_casUpdatesNoRow_throwsConflict() {
        when(driverRepository.existsById(USER_ID)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> driverService.reserveDriver(USER_ID, UUID.randomUUID(), 5));

        assertEquals("DRIVER_ALREADY_RESERVED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("reserveDriver throws 404 DRIVER_NOT_FOUND for an unknown driver")
    void reserveDriver_unknownDriver_throwsNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.reserveDriver(USER_ID, UUID.randomUUID(), 5));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DRIVER_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("releaseReservation does not look the driver up when a reservation was released")
    void releaseReservation_reservedDriver_releases() {
        when(driverRepository.releaseReservation(eq(USER_ID), eq(PASSENGER_ID), any(Instant.class))).thenReturn(1);

        driverService.releaseReservation(USER_ID, PASSENGER_ID);

        verify(driverRepository, never()).existsById(any());
    }

    @Test
    @DisplayName("releaseReservation is idempotent: a driver that is not RESERVED is not an error")
    void releaseReservation_driverNotReserved_doesNothing() {
        when(driverRepository.existsById(USER_ID)).thenReturn(true);

        assertDoesNotThrow(() -> driverService.releaseReservation(USER_ID, PASSENGER_ID));
    }

    @Test
    @DisplayName("releaseReservation throws 404 DRIVER_NOT_FOUND for an unknown driver")
    void releaseReservation_unknownDriver_throwsNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.releaseReservation(USER_ID, PASSENGER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DRIVER_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("releaseExpiredReservations returns the number of drivers released")
    void releaseExpiredReservations_expiredLeases_returnsCount() {
        when(driverRepository.releaseExpiredReservations(any(Instant.class))).thenReturn(2);

        assertEquals(2, driverService.releaseExpiredReservations());
    }

    // ---- GET /api/drivers/available ----

    private static Driver availableDriver(String name, double lat, double lng) {
        return Driver.builder()
                .driverId(UUID.randomUUID())
                .fullName(name)
                .status(DriverStatus.AVAILABLE)
                .currentLat(BigDecimal.valueOf(lat))
                .currentLng(BigDecimal.valueOf(lng))
                .lastHeartbeatAt(Instant.parse("2026-10-09T10:00:00Z"))
                .version(3)
                .build();
    }

    @Test
    @DisplayName("findAvailableDrivers keeps drivers inside the radius, nearest first, and drops box corners outside it")
    void findAvailableDrivers_withPoint_filtersByRadiusAndSortsByDistance() {
        Driver hangBai = availableDriver("Hang Bai", 21.0190, 105.8520);      // 1.08 km
        Driver trangTien = availableDriver("Trang Tien", 21.0250, 105.8570);  // 0.49 km
        // Inside the 5 km bounding box (both deltas ~0.044 deg) but ~6.2 km away diagonally
        Driver corner = availableDriver("Corner", 21.0285 + 0.040, 105.8542 + 0.042);
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
                eq(DriverStatus.AVAILABLE), any(), any(), any(), any()))
                .thenReturn(List.of(hangBai, corner, trangTien));

        List<AvailableDriverResponse> result = driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, 0, 20, false);

        assertEquals(List.of("Trang Tien", "Hang Bai"), result.stream().map(AvailableDriverResponse::getFullName).toList());
        assertEquals(0.49, result.get(0).getDistanceKm());
        assertEquals(1.08, result.get(1).getDistanceKm());
        assertEquals(3, result.get(0).getVersion());
        assertEquals(trangTien.getDriverId(), result.get(0).getId());
        assertEquals(DriverStatus.AVAILABLE, result.get(0).getStatus());
    }

    @Test
    @DisplayName("findAvailableDrivers queries the bounding box around the point")
    void findAvailableDrivers_withPoint_queriesBoundingBox() {
        ArgumentCaptor<BigDecimal> minLat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxLat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> minLng = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxLng = ArgumentCaptor.forClass(BigDecimal.class);
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
                eq(DriverStatus.AVAILABLE), minLat.capture(), maxLat.capture(), minLng.capture(), maxLng.capture()))
                .thenReturn(List.of());

        driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, 0, 20, false);

        GeoUtils.BoundingBox box = GeoUtils.boundingBox(21.0285, 105.8542, 5.0);
        assertEquals(box.minLat(), minLat.getValue().doubleValue(), 1e-12);
        assertEquals(box.maxLat(), maxLat.getValue().doubleValue(), 1e-12);
        assertEquals(box.minLng(), minLng.getValue().doubleValue(), 1e-12);
        assertEquals(box.maxLng(), maxLng.getValue().doubleValue(), 1e-12);
    }

    @Test
    @DisplayName("findAvailableDrivers returns at most limit drivers, the nearest ones")
    void findAvailableDrivers_limit_keepsNearest() {
        Driver hangBai = availableDriver("Hang Bai", 21.0190, 105.8520);      // 1.08 km
        Driver trangTien = availableDriver("Trang Tien", 21.0250, 105.8570);  // 0.49 km
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
                eq(DriverStatus.AVAILABLE), any(), any(), any(), any()))
                .thenReturn(List.of(hangBai, trangTien));

        List<AvailableDriverResponse> result = driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, 0, 1, false);

        assertEquals(List.of("Trang Tien"), result.stream().map(AvailableDriverResponse::getFullName).toList());
    }

    // ---- PATCH /api/drivers/{id}/status ----

    private Driver driverWithStatus(DriverStatus status) {
        return Driver.builder().driverId(USER_ID).fullName("Tran Van B").status(status).version(2)
                .updatedAt(Instant.parse("2026-10-09T10:00:00Z")).build();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"OFFLINE, AVAILABLE", "AVAILABLE, OFFLINE"})
    @DisplayName("updateStatus allows a driver to switch between OFFLINE and AVAILABLE")
    void updateStatus_allowedTransition_savesNewStatus(DriverStatus from, DriverStatus to) {
        Driver driver = driverWithStatus(from);
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(driver)).thenReturn(driver);

        DriverStatusResponse response = driverService.updateStatus(USER_ID, to, USER_ID);

        assertEquals(to, response.getStatus());
        assertEquals(USER_ID, response.getId());
        verify(driverRepository).saveAndFlush(driver);
    }

    @ParameterizedTest(name = "{0} -> {0}")
    @CsvSource({"OFFLINE", "AVAILABLE"})
    @DisplayName("updateStatus to the current status is an idempotent no-op")
    void updateStatus_sameStatus_returnsWithoutSaving(DriverStatus status) {
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driverWithStatus(status)));

        DriverStatusResponse response = driverService.updateStatus(USER_ID, status, USER_ID);

        assertEquals(status, response.getStatus());
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "AVAILABLE, RESERVED", "AVAILABLE, BUSY", "OFFLINE, RESERVED", "OFFLINE, BUSY",
            "RESERVED, OFFLINE", "RESERVED, AVAILABLE", "BUSY, OFFLINE", "BUSY, AVAILABLE", "BUSY, BUSY"
    })
    @DisplayName("updateStatus rejects any transition to or from RESERVED/BUSY with 422")
    void updateStatus_systemManagedStatus_throws422(DriverStatus from, DriverStatus to) {
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driverWithStatus(from)));

        BusinessException ex = assertThrows(BusinessException.class, () -> driverService.updateStatus(USER_ID, to, USER_ID));

        assertEquals(422, ex.getStatus().value());
        assertEquals("INVALID_STATUS_TRANSITION", ex.getCode());
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateStatus on another driver's profile throws 403 NOT_OWN_RESOURCE without reading it")
    void updateStatus_otherDriver_throwsForbidden() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.updateStatus(USER_ID, DriverStatus.AVAILABLE, UUID.randomUUID()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("NOT_OWN_RESOURCE", ex.getCode());
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("updateStatus for a user without a driver profile throws 404 DRIVER_NOT_FOUND")
    void updateStatus_noProfile_throwsNotFound() {
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.updateStatus(USER_ID, DriverStatus.AVAILABLE, USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DRIVER_NOT_FOUND", ex.getCode());
    }

    // ---- PATCH /api/drivers/{id}/location ----

    @Test
    @DisplayName("updateLocation stores the position rounded to 6 decimals through the version-neutral update")
    void updateLocation_ownProfile_storesRoundedPosition() {
        ArgumentCaptor<BigDecimal> lat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> lng = ArgumentCaptor.forClass(BigDecimal.class);
        when(driverRepository.updateLocation(eq(USER_ID), lat.capture(), lng.capture(), any(Instant.class))).thenReturn(1);

        DriverLocationResponse response = driverService.updateLocation(USER_ID, 21.03000049, 105.8560006, USER_ID);

        assertEquals(new BigDecimal("21.030000"), lat.getValue());
        assertEquals(new BigDecimal("105.856001"), lng.getValue());
        assertEquals(USER_ID, response.getId());
        assertEquals(21.03, response.getLocation().getLatitude());
        assertEquals(105.856001, response.getLocation().getLongitude());
        assertNotNull(response.getLocation().getUpdatedAt());
        // Never loads and saves the entity, which would bump @Version and break a pending reservation CAS
        verify(driverRepository, never()).saveAndFlush(any());
        verify(driverRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateLocation on another driver's profile throws 403 NOT_OWN_RESOURCE")
    void updateLocation_otherDriver_throwsForbidden() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.updateLocation(USER_ID, 21.0, 105.8, UUID.randomUUID()));

        assertEquals("NOT_OWN_RESOURCE", ex.getCode());
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("updateLocation for a user without a driver profile throws 404 DRIVER_NOT_FOUND")
    void updateLocation_noProfile_throwsNotFound() {
        when(driverRepository.updateLocation(eq(USER_ID), any(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> driverService.updateLocation(USER_ID, 21.0, 105.8, USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DRIVER_NOT_FOUND", ex.getCode());
    }

    // ---- GET /api/drivers/{id} ----

    @Test
    @DisplayName("getDriver returns profile, the active vehicle and the last position")
    void getDriver_existingDriver_returnsDetails() {
        Driver driver = availableDriver("Tran Van Binh", 21.0250, 105.8570);
        Vehicle inactive = Vehicle.builder().driver(driver).licensePlate("30A-99999").make("Old").model("Car")
                .color("Black").year((short) 2010).vehicleType(VehicleType.SEDAN).active(false).build();
        Vehicle active = Vehicle.builder().driver(driver).licensePlate("30A-10001").make("Toyota").model("Vios")
                .color("White").year((short) 2021).vehicleType(VehicleType.SEDAN).active(true).build();
        when(driverRepository.findById(driver.getDriverId())).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriver(driver)).thenReturn(List.of(inactive, active));

        DriverDetailResponse response = driverService.getDriver(driver.getDriverId(), PASSENGER_ID);

        assertEquals(driver.getDriverId(), response.getId());
        assertEquals("Tran Van Binh", response.getFullName());
        assertEquals(DriverStatus.AVAILABLE, response.getStatus());
        assertEquals("30A-10001", response.getVehicle().getLicensePlate());
        assertEquals(21.0250, response.getLocation().getLatitude());
    }

    @Test
    @DisplayName("getDriver without vehicle or position returns them as null")
    void getDriver_noVehicleNoPosition_returnsNulls() {
        Driver driver = Driver.builder().driverId(USER_ID).fullName("New Driver").status(DriverStatus.OFFLINE).build();
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriver(driver)).thenReturn(List.of());

        DriverDetailResponse response = driverService.getDriver(USER_ID, USER_ID);

        assertNull(response.getVehicle());
        assertNull(response.getLocation());
    }

    @Test
    @DisplayName("getDriver hides an OFFLINE driver's position from other users but shows it to the driver")
    void getDriver_offlineDriver_positionOnlyForThatDriver() {
        Driver driver = availableDriver("Off Duty", 21.0250, 105.8570);
        driver.setStatus(DriverStatus.OFFLINE);
        when(driverRepository.findById(driver.getDriverId())).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByDriver(driver)).thenReturn(List.of());

        DriverDetailResponse forOthers = driverService.getDriver(driver.getDriverId(), PASSENGER_ID);
        DriverDetailResponse forSelf = driverService.getDriver(driver.getDriverId(), driver.getDriverId());

        assertNull(forOthers.getLocation());
        assertEquals(DriverStatus.OFFLINE, forOthers.getStatus());
        assertEquals(21.0250, forSelf.getLocation().getLatitude());
    }

    @Test
    @DisplayName("getDriver with an unknown id throws 404 DRIVER_NOT_FOUND")
    void getDriver_unknownId_throwsNotFound() {
        when(driverRepository.findById(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> driverService.getDriver(USER_ID, PASSENGER_ID));

        assertEquals("DRIVER_NOT_FOUND", ex.getCode());
    }

    // ---- GET /api/drivers/nearby ----

    @Test
    @DisplayName("findNearbyDrivers returns at most limit drivers within the radius, nearest first")
    void findNearbyDrivers_limit_returnsNearestOnly() {
        Driver hangBai = availableDriver("Hang Bai", 21.0190, 105.8520);      // 1.08 km
        Driver trangTien = availableDriver("Trang Tien", 21.0250, 105.8570);  // 0.49 km
        Driver hangNgang = availableDriver("Hang Ngang", 21.0345, 105.8505);  // 0.77 km
        Driver corner = availableDriver("Corner", 21.0285 + 0.040, 105.8542 + 0.042);  // ~6.2 km
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
                eq(DriverStatus.AVAILABLE), any(), any(), any(), any()))
                .thenReturn(List.of(hangBai, corner, trangTien, hangNgang));

        List<NearbyDriverResponse> result = driverService.findNearbyDrivers(21.0285, 105.8542, 5.0, 0, 2, false);

        assertEquals(List.of("Trang Tien", "Hang Ngang"), result.stream().map(NearbyDriverResponse::getFullName).toList());
        assertEquals(0.49, result.get(0).getDistanceKm());
        assertEquals(21.0250, result.get(0).getLocation().getLatitude());
    }

    // ---- pagination and sort of /available and /nearby (docs/api.md §8) ----

    // Trang Tien 0.49 km, Hang Ngang 0.77 km, Hang Bai 1.08 km from the reference point
    private void threeDriversInRadius() {
        when(driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(
                eq(DriverStatus.AVAILABLE), any(), any(), any(), any()))
                .thenReturn(List.of(
                        availableDriver("Hang Bai", 21.0190, 105.8520),
                        availableDriver("Trang Tien", 21.0250, 105.8570),
                        availableDriver("Hang Ngang", 21.0345, 105.8505)));
    }

    private List<String> availableNames(int page, int size, boolean farthestFirst) {
        return driverService.findAvailableDrivers(21.0285, 105.8542, 5.0, page, size, farthestFirst).stream()
                .map(AvailableDriverResponse::getFullName).toList();
    }

    @Test
    @DisplayName("findAvailableDrivers returns the requested page: page 1 of size 2 holds the third nearest driver")
    void findAvailableDrivers_secondPage_returnsNextDrivers() {
        threeDriversInRadius();

        assertEquals(List.of("Trang Tien", "Hang Ngang"), availableNames(0, 2, false));
        assertEquals(List.of("Hang Bai"), availableNames(1, 2, false));
    }

    @Test
    @DisplayName("findAvailableDrivers returns an empty list for a page after the last one")
    void findAvailableDrivers_pageAfterLast_returnsEmptyList() {
        threeDriversInRadius();

        assertEquals(List.of(), availableNames(2, 2, false));
        assertEquals(List.of(), availableNames(Integer.MAX_VALUE, 50, false));
    }

    @Test
    @DisplayName("findAvailableDrivers sorted descending lists the farthest driver first, and pages in that order")
    void findAvailableDrivers_farthestFirst_reversesOrder() {
        threeDriversInRadius();

        assertEquals(List.of("Hang Bai", "Hang Ngang", "Trang Tien"), availableNames(0, 20, true));
        assertEquals(List.of("Trang Tien"), availableNames(1, 2, true));
    }

    @Test
    @DisplayName("findNearbyDrivers pages and sorts the same way as findAvailableDrivers")
    void findNearbyDrivers_pageAndSort_returnsRequestedPage() {
        threeDriversInRadius();

        List<NearbyDriverResponse> secondPage = driverService.findNearbyDrivers(21.0285, 105.8542, 5.0, 1, 1, false);
        List<NearbyDriverResponse> farthest = driverService.findNearbyDrivers(21.0285, 105.8542, 5.0, 0, 1, true);

        assertEquals(List.of("Hang Ngang"), secondPage.stream().map(NearbyDriverResponse::getFullName).toList());
        assertEquals(List.of("Hang Bai"), farthest.stream().map(NearbyDriverResponse::getFullName).toList());
    }
}

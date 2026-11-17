package com.example.driverservice.service;

import com.example.driverservice.config.ReservationProperties;
import com.example.driverservice.dto.request.CreateDriverRequest;
import com.example.driverservice.dto.request.VehicleRequest;
import com.example.driverservice.dto.response.AvailableDriverResponse;
import com.example.driverservice.dto.response.DriverDetailResponse;
import com.example.driverservice.dto.response.DriverLocationResponse;
import com.example.driverservice.dto.response.LocationResponse;
import com.example.driverservice.dto.response.NearbyDriverResponse;
import com.example.driverservice.dto.response.DriverProfileResponse;
import com.example.driverservice.dto.response.DriverStatusResponse;
import com.example.driverservice.dto.response.ReserveDriverResponse;
import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.Vehicle;
import com.example.driverservice.exception.BusinessException;
import com.example.driverservice.exception.ConflictException;
import com.example.driverservice.exception.ResourceNotFoundException;
import com.example.driverservice.mapper.DriverMapper;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ReservationProperties.class)
public class DriverServiceImpl implements DriverService {

    private static final String DRIVER_PROFILE_EXISTS = "DRIVER_PROFILE_EXISTS";
    private static final String LICENSE_PLATE_ALREADY_REGISTERED = "LICENSE_PLATE_ALREADY_REGISTERED";
    private static final String DRIVER_ALREADY_RESERVED = "DRIVER_ALREADY_RESERVED";

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverMapper driverMapper;
    private final ReservationProperties reservationProperties;

    @Override
    @Transactional
    public DriverProfileResponse createDriver(CreateDriverRequest request) {
        VehicleRequest vehicleRequest = request.getVehicle();
        int maxYear = Year.now(ZoneOffset.UTC).getValue() + 1;
        if (vehicleRequest.getYear() > maxYear) {
            throw new BusinessException("Vehicle year must be " + maxYear + " or earlier",
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }

        String licensePlate = normalizeLicensePlate(vehicleRequest.getLicensePlate());
        // The driver id is the identity user id, so an existing row means this user already has a profile
        if (driverRepository.existsById(request.getUserId())) {
            throw new ConflictException("Driver profile already exists for this user", DRIVER_PROFILE_EXISTS);
        }
        if (vehicleRepository.existsByLicensePlate(licensePlate)) {
            throw new ConflictException("License plate already registered", LICENSE_PLATE_ALREADY_REGISTERED);
        }

        Driver driver = Driver.builder()
                .driverId(request.getUserId())
                .fullName(request.getFullName().trim())
                .licenseNumber(request.getLicenseNumber())
                .status(DriverStatus.OFFLINE)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .driver(driver)
                .licensePlate(licensePlate)
                .make(vehicleRequest.getMake().trim())
                .model(vehicleRequest.getModel().trim())
                .color(vehicleRequest.getColor().trim())
                .year(vehicleRequest.getYear().shortValue())
                .vehicleType(vehicleRequest.getVehicleType())
                .build();

        try {
            // Flush now so a concurrent insert hitting a unique index surfaces here as 409, not at commit as 500
            driverRepository.saveAndFlush(driver);
            vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException ex) {
            String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (detail.contains(Vehicle.UQ_LICENSE_PLATE)) {
                throw new ConflictException("License plate already registered", LICENSE_PLATE_ALREADY_REGISTERED);
            }
            if (detail.contains(Driver.PRIMARY_KEY)) {
                throw new ConflictException("Driver profile already exists for this user", DRIVER_PROFILE_EXISTS);
            }
            throw ex;
        }

        log.info("Driver profile created driverId={}", driver.getDriverId());
        return driverMapper.toProfileResponse(driver, vehicle);
    }

    @Override
    @Transactional
    public void deleteDriverByUserId(UUID userId) {
        Optional<Driver> found = driverRepository.findById(userId);
        if (found.isEmpty()) {
            // Idempotent: the compensation may run when the create never reached this service
            log.info("No driver profile to delete for userId={}", userId);
            return;
        }

        Driver driver = found.get();
        // Only a profile that was never used can be removed; this is a compensation, not a general delete
        if (driver.getStatus() != DriverStatus.OFFLINE || driver.getReservedForTrip() != null) {
            throw new ConflictException("Driver profile is in use and cannot be deleted", "DRIVER_PROFILE_IN_USE");
        }

        vehicleRepository.deleteAll(vehicleRepository.findByDriver(driver));
        driverRepository.delete(driver);
        log.info("Driver profile deleted driverId={}", driver.getDriverId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableDriverResponse> findAvailableDrivers(double latitude, double longitude, double radiusKm,
                                                              int page, int size, boolean farthestFirst) {
        return availableWithin(latitude, longitude, radiusKm, page, size, farthestFirst).stream()
                .map(candidate -> driverMapper.toAvailableResponse(candidate.driver(), roundKm(candidate.distanceKm())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NearbyDriverResponse> findNearbyDrivers(double latitude, double longitude, double radiusKm,
                                                        int page, int size, boolean farthestFirst) {
        return availableWithin(latitude, longitude, radiusKm, page, size, farthestFirst).stream()
                .map(candidate -> driverMapper.toNearbyResponse(candidate.driver(), roundKm(candidate.distanceKm())))
                .toList();
    }

    // One page of the AVAILABLE drivers within radiusKm of the point, ordered by distance
    private List<Candidate> availableWithin(double latitude, double longitude, double radiusKm,
                                            int page, int size, boolean farthestFirst) {
        Comparator<Candidate> byDistance = Comparator.comparingDouble(Candidate::distanceKm);
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(latitude, longitude, radiusKm);
        return driverRepository.findByStatusAndCurrentLatBetweenAndCurrentLngBetween(DriverStatus.AVAILABLE,
                        BigDecimal.valueOf(box.minLat()), BigDecimal.valueOf(box.maxLat()),
                        BigDecimal.valueOf(box.minLng()), BigDecimal.valueOf(box.maxLng()))
                .stream()
                .map(driver -> new Candidate(driver, GeoUtils.haversineKm(latitude, longitude,
                        driver.getCurrentLat().doubleValue(), driver.getCurrentLng().doubleValue())))
                // The box corners are farther than the radius, so keep only drivers inside the circle
                .filter(candidate -> candidate.distanceKm() <= radiusKm)
                .sorted(farthestFirst ? byDistance.reversed() : byDistance)
                // long: page * size must not overflow for a very large page number
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @Override
    @Transactional
    public DriverStatusResponse updateStatus(UUID driverId, DriverStatus target, UUID callerId) {
        requireOwnProfile(driverId, callerId);
        Driver driver = findDriver(driverId);
        DriverStatus current = driver.getStatus();

        // A driver only switches OFFLINE ⇄ AVAILABLE; RESERVED and BUSY are set by the system (docs/api.md)
        if (!isDriverSettable(target) || !isDriverSettable(current)) {
            throw new BusinessException("Status cannot be changed from " + current + " to " + target,
                    HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_STATUS_TRANSITION");
        }
        if (current == target) {
            // PATCH is idempotent: asking for the current status changes nothing
            return driverMapper.toStatusResponse(driver);
        }

        driver.setStatus(target);
        // Flush now so a concurrent change (e.g. a reservation) fails the @Version check here as 409, not at commit
        Driver saved = driverRepository.saveAndFlush(driver);
        log.info("Driver status changed driverId={}, {} -> {}", driverId, current, target);
        return driverMapper.toStatusResponse(saved);
    }

    @Override
    @Transactional
    public DriverLocationResponse updateLocation(UUID driverId, double latitude, double longitude, UUID callerId) {
        requireOwnProfile(driverId, callerId);
        // drivers.current_lat/current_lng are DECIMAL(9, 6)
        BigDecimal lat = BigDecimal.valueOf(latitude).setScale(6, RoundingMode.HALF_UP);
        BigDecimal lng = BigDecimal.valueOf(longitude).setScale(6, RoundingMode.HALF_UP);
        Instant now = Instant.now();
        if (driverRepository.updateLocation(driverId, lat, lng, now) == 0) {
            throw new ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND");
        }
        return DriverLocationResponse.builder()
                .id(driverId)
                .location(LocationResponse.builder()
                        .latitude(lat.doubleValue())
                        .longitude(lng.doubleValue())
                        .updatedAt(now)
                        .build())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DriverDetailResponse getDriver(UUID driverId, UUID callerId) {
        Driver driver = findDriver(driverId);
        Vehicle activeVehicle = vehicleRepository.findByDriver(driver).stream()
                .filter(vehicle -> Boolean.TRUE.equals(vehicle.getActive()))
                .findFirst()
                .orElse(null);
        // Off duty, the last position is often the driver's home, so only the driver may see it
        boolean showLocation = driver.getStatus() != DriverStatus.OFFLINE || driverId.equals(callerId);
        return driverMapper.toDetailResponse(driver, activeVehicle, showLocation);
    }

    @Override
    @Transactional
    public ReserveDriverResponse reserveDriver(UUID driverId, UUID tripId, int expectedVersion) {
        // reserved_until is TIMESTAMPTZ (microseconds); milliseconds keep the response equal to what is stored
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant reservedUntil = now.plusSeconds(reservationProperties.leaseSeconds());

        if (driverRepository.reserve(driverId, tripId, expectedVersion, reservedUntil, now) == 0) {
            if (!driverRepository.existsById(driverId)) {
                throw new ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND");
            }
            log.info("Driver reservation rejected driverId={}, tripId={}, expectedVersion={}", driverId, tripId, expectedVersion);
            throw new ConflictException("Driver is not available or was changed by another request", DRIVER_ALREADY_RESERVED);
        }

        log.info("Driver reserved driverId={}, tripId={}, reservedUntil={}", driverId, tripId, reservedUntil);
        return ReserveDriverResponse.builder()
                .driverId(driverId)
                .status(DriverStatus.RESERVED)
                .reservedForTrip(tripId)
                .reservedUntil(reservedUntil)
                .version(expectedVersion + 1)
                .build();
    }

    @Override
    @Transactional
    public void releaseReservation(UUID driverId) {
        if (driverRepository.releaseReservation(driverId, Instant.now()) == 1) {
            log.info("Driver reservation released driverId={}", driverId);
            return;
        }
        if (!driverRepository.existsById(driverId)) {
            throw new ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND");
        }
        // Idempotent: the compensation may be repeated, or arrive after the lease already expired
        log.info("No reservation to release for driverId={}", driverId);
    }

    @Override
    @Transactional
    public int releaseExpiredReservations() {
        int released = driverRepository.releaseExpiredReservations(Instant.now());
        if (released > 0) {
            log.info("Reservation lease expired for {} driver(s), returned to AVAILABLE", released);
        }
        return released;
    }

    private static boolean isDriverSettable(DriverStatus status) {
        return status == DriverStatus.OFFLINE || status == DriverStatus.AVAILABLE;
    }

    // A driver's id is its user id, so "own resource" means the path id equals the token's user id
    private void requireOwnProfile(UUID driverId, UUID callerId) {
        if (!driverId.equals(callerId)) {
            throw new BusinessException("Drivers can only change their own profile",
                    HttpStatus.FORBIDDEN, "NOT_OWN_RESOURCE");
        }
    }

    private Driver findDriver(UUID driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found", "DRIVER_NOT_FOUND"));
    }

    private static double roundKm(double km) {
        return Math.round(km * 100) / 100.0;
    }

    private record Candidate(Driver driver, double distanceKm) {
    }

    // 29a-123.45 and 29A-12345 are the same plate, so store one canonical form
    private String normalizeLicensePlate(String licensePlate) {
        return licensePlate.trim().toUpperCase(Locale.ROOT).replace(".", "");
    }
}

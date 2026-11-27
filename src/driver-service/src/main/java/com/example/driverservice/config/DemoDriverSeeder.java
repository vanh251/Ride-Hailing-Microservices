package com.example.driverservice.config;

import com.example.driverservice.entity.Driver;
import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.Vehicle;
import com.example.driverservice.entity.VehicleType;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.VehicleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creates the driver profiles of identity-service's 10 demo DRIVER accounts so matching has drivers to pick from.
 * driver_id = the fixed user ids identity-service seeds (d0000000-0000-4000-8000-0000000000NN); positions are
 * simulated around Hoan Kiem Lake, Hanoi. Existing profiles are left untouched, so restarts are safe.
 * Turn it off with SEED_DATA_ENABLED=false. Data and distances: tests/data/driver/README.md
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DemoDriverSeeder implements ApplicationRunner {

    static final List<DemoDriver> DEMO_DRIVERS = List.of(
            new DemoDriver(1, "Tran Van Binh", DriverStatus.AVAILABLE, 21.0250, 105.8570,
                    new DemoVehicle("30A-10001", "Toyota", "Vios", "White", 2021, VehicleType.SEDAN)),
            new DemoDriver(2, "Le Hoang Nam", DriverStatus.AVAILABLE, 21.0190, 105.8520,
                    new DemoVehicle("30A-10002", "Honda", "City", "Silver", 2020, VehicleType.SEDAN)),
            new DemoDriver(3, "Pham Minh Tuan", DriverStatus.AVAILABLE, 21.0345, 105.8505,
                    new DemoVehicle("29B1-10003", "Honda", "Wave", "Red", 2019, VehicleType.MOTORBIKE)),
            new DemoDriver(4, "Hoang Duc Anh", DriverStatus.AVAILABLE, 21.0450, 105.8800,
                    new DemoVehicle("30A-10004", "Ford", "Everest", "Black", 2022, VehicleType.SUV)),
            new DemoDriver(5, "Vu Thi Lan", DriverStatus.AVAILABLE, 21.0368, 105.8342,
                    new DemoVehicle("30A-10005", "Hyundai", "Accent", "Blue", 2021, VehicleType.SEDAN)),
            new DemoDriver(6, "Do Quang Huy", DriverStatus.AVAILABLE, 21.0060, 105.8590,
                    new DemoVehicle("30A-10006", "Kia", "Carnival", "Gray", 2023, VehicleType.VAN)),
            new DemoDriver(7, "Bui Van Long", DriverStatus.AVAILABLE, 21.0180, 105.8290,
                    new DemoVehicle("29B1-10007", "Yamaha", "Exciter", "Blue", 2020, VehicleType.MOTORBIKE)),
            // Outside a 5 km search around Hoan Kiem, to show the radius filter
            new DemoDriver(8, "Dang Thi Mai", DriverStatus.AVAILABLE, 21.0368, 105.7905,
                    new DemoVehicle("30A-10008", "Mazda", "3", "Red", 2022, VehicleType.SEDAN)),
            new DemoDriver(9, "Ngo Thanh Son", DriverStatus.OFFLINE, 21.0680, 105.8240,
                    new DemoVehicle("30A-10009", "Toyota", "Fortuner", "White", 2019, VehicleType.SUV)),
            // Never reported a position
            new DemoDriver(10, "Duong Van Khoa", DriverStatus.OFFLINE, null, null,
                    new DemoVehicle("30A-10010", "Kia", "Morning", "Yellow", 2018, VehicleType.SEDAN))
    );

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final TransactionOperations transactions;

    public DemoDriverSeeder(DriverRepository driverRepository,
                            VehicleRepository vehicleRepository,
                            TransactionOperations transactions) {
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.transactions = transactions;
    }

    /** Same ids as identity-service's demo DRIVER accounts (DemoUserSeeder.driverId). */
    static UUID driverId(int number) {
        return UUID.fromString(String.format("d0000000-0000-4000-8000-%012d", number));
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        int failed = 0;
        for (DemoDriver demo : DEMO_DRIVERS) {
            UUID id = driverId(demo.number());
            if (driverRepository.existsById(id)) {
                continue;
            }
            try {
                // Driver and vehicle together, so a failure never leaves a profile without its vehicle
                transactions.executeWithoutResult(status -> insert(demo));
                created++;
            } catch (DataAccessException ex) {
                if (ex instanceof DataIntegrityViolationException && driverRepository.existsById(id)) {
                    // Another driver-service instance inserted the same profile between the check and the insert
                    log.info("Demo driver {} was created by another instance, skipping", id);
                } else {
                    // Demo data must never stop the service from starting (matching-service waits for it to be healthy)
                    log.warn("Demo driver {} could not be created: {}", id, ex.getMostSpecificCause().getMessage());
                    failed++;
                }
            }
        }
        log.info("Demo driver seeding finished: created={}, alreadyPresent={}, failed={}",
                created, DEMO_DRIVERS.size() - created - failed, failed);
        if (failed > 0) {
            log.warn("{} demo driver(s) were not created, see the warnings above. If driver_db was created before "
                    + "driver_id became the user id, recreate it (tests/data/driver/README.md)", failed);
        }
    }

    private void insert(DemoDriver demo) {
        boolean located = demo.latitude() != null;
        Driver driver = driverRepository.saveAndFlush(Driver.builder()
                .driverId(driverId(demo.number()))
                .fullName(demo.fullName())
                .licenseNumber(String.format("0123456780%02d", demo.number()))
                .status(demo.status())
                .currentLat(located ? BigDecimal.valueOf(demo.latitude()) : null)
                .currentLng(located ? BigDecimal.valueOf(demo.longitude()) : null)
                .lastHeartbeatAt(located ? Instant.now() : null)
                .build());
        DemoVehicle v = demo.vehicle();
        vehicleRepository.saveAndFlush(Vehicle.builder()
                .driver(driver)
                .licensePlate(v.licensePlate())
                .make(v.make())
                .model(v.model())
                .color(v.color())
                .year((short) v.year())
                .vehicleType(v.type())
                .build());
    }

    record DemoDriver(int number, String fullName, DriverStatus status, Double latitude, Double longitude,
                      DemoVehicle vehicle) {
    }

    record DemoVehicle(String licensePlate, String make, String model, String color, int year, VehicleType type) {
    }
}

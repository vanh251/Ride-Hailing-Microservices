package com.example.driverservice;

import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import com.example.driverservice.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class DriverServiceApplicationTests {

    // The test profile excludes the DataSource, so the JPA repositories are mocked
    @MockitoBean
    private DriverRepository driverRepository;

    @MockitoBean
    private VehicleRepository vehicleRepository;

    @MockitoBean
    private ProcessedEventRepository processedEventRepository;

    @Test
    void contextLoads() {
    }

}

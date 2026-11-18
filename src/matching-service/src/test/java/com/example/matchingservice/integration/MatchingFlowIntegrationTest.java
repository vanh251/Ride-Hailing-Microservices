package com.example.matchingservice.integration;

import com.example.matchingservice.client.*;
import com.example.matchingservice.dto.*;
import com.example.matchingservice.entity.*;
import com.example.matchingservice.event.*;
import com.example.matchingservice.repository.*;
import com.example.matchingservice.service.*;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(MatchingFlowIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named = "M2_TEST_DB_URL", matches = ".+")
class MatchingFlowIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = MatchRequestRepository.class)
    @Import({ MatchingServiceImpl.class, MatchingEventPublisher.class })
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("M2_TEST_DB_URL"), "m2_test", "m2_test");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(source);
            f.setPackagesToScan("com.example.matchingservice.entity");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.default_schema",
                    "m2_matching", "hibernate.hbm2ddl.create_namespaces", "true"));
            return f;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Bean
        DriverServiceClient driver() {
            return mock(DriverServiceClient.class);
        }

        @Bean
        TripServiceClient trip() {
            return mock(TripServiceClient.class);
        }

    }

    @Autowired
    MatchingService service;

    @Autowired
    MatchRequestRepository requests;

    @Autowired
    ProcessedEventRepository processed;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    DriverServiceClient drivers;

    @Autowired
    TripServiceClient trips;

    UUID driver;

    @BeforeEach
    void boundary() {
        reset(drivers, trips);
        driver = UUID.randomUUID();
        var candidate = AvailableDriverDto.builder()
            .id(driver)
            .fullName("Driver")
            .status("AVAILABLE")
            .version(1)
            .distanceKm(0.5)
            .location(AvailableDriverDto.DriverLocation.builder().latitude(10.77).longitude(106.7).build())
            .build();
        when(drivers.getAvailableDrivers(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(candidate));
        when(trips.getTripStatus(any())).thenReturn("SEARCHING");
        when(drivers.reserveDriver(eq(driver), any(), eq(1))).thenAnswer(i -> ReserveDriverResponse.builder()
            .driverId(driver)
            .reservedForTrip(i.getArgument(1))
            .status("RESERVED")
            .version(2)
            .reservedUntil(Instant.now().plusSeconds(30))
            .build());
    }

    EventEnvelope<Map<String, Object>> event() {
        UUID trip = UUID.randomUUID();
        Instant requestedAt = Instant.now();
        return EventEnvelope.<Map<String, Object>>builder()
            .eventId(UUID.randomUUID())
            .eventType("RideRequested")
            .aggregateId(trip.toString())
            .aggregateType("Trip")
            .producer("trip-service")
            .correlationId(UUID.randomUUID().toString())
            .occurredAt(requestedAt)
            .payload(Map.of("tripId", trip.toString(), "passengerId", UUID.randomUUID().toString(), "pickup",
                    Map.of("latitude", 10.77, "longitude", 106.7), "requestedAt", requestedAt.toString()))
            .build();
    }

    @Test
    void duplicateEventCommitsOneRequestAndOutbox() {
        var e = event();
        service.handleRideRequested(e);
        service.handleRideRequested(e);
        UUID id = UUID.fromString(e.getAggregateId());
        assertEquals(MatchStatus.MATCHED, requests.findById(id).orElseThrow().getStatus());
        assertEquals(1, outbox.findAll().stream().filter(o -> o.getAggregateId().equals(id.toString())).count());
        verify(drivers, times(1)).reserveDriver(driver, id, 1);
    }

    @Test
    void commitFailureRollsBackAndReleasesExternalReservation() {
        var e = event();
        UUID trip = UUID.fromString(e.getAggregateId());
        when(drivers.reserveDriver(driver, trip, 1)).thenAnswer(i -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("simulated commit failure");
                }
            });
            return ReserveDriverResponse.builder()
                .driverId(driver)
                .reservedForTrip(trip)
                .status("RESERVED")
                .version(2)
                .reservedUntil(Instant.now().plusSeconds(30))
                .build();
        });
        assertThrows(IllegalStateException.class, () -> service.handleRideRequested(e));
        assertFalse(requests.existsById(trip));
        assertFalse(processed.existsById(new ProcessedEventId(e.getEventId(), "matching-service.trip-events")));
        assertEquals(0, outbox.findAll().stream().filter(o -> o.getAggregateId().equals(trip.toString())).count());
        verify(drivers).releaseDriverReservation(driver, trip);
    }

    @Test
    void concurrentCreationRetriesWithoutSecondReservation() throws Exception {
        var event = event();
        UUID trip = UUID.fromString(event.getAggregateId());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                start.await();
                service.handleRideRequested(event);
                return true;
            });
            var b = executor.submit(() -> {
                start.await();
                service.handleRideRequested(event);
                return true;
            });
            start.countDown();
            for (var future : List.of(a, b)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                }
                catch (ExecutionException ex) {
                    assertTrue(ex.getCause() instanceof org.springframework.dao.DataAccessException,
                            ex.getCause().toString());
                }
            }
            service.handleRideRequested(event);
            assertEquals(MatchStatus.MATCHED, requests.findById(trip).orElseThrow().getStatus());
            verify(drivers, times(1)).reserveDriver(driver, trip, 1);
        }
        finally {
            executor.shutdownNow();
        }
    }

}

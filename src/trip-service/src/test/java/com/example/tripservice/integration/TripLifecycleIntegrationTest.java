package com.example.tripservice.integration;

import com.example.tripservice.client.*;
import com.example.tripservice.dto.*;
import com.example.tripservice.entity.*;
import com.example.tripservice.event.*;
import com.example.tripservice.mapper.TripMapper;
import com.example.tripservice.repository.*;
import com.example.tripservice.service.*;
import com.example.tripservice.exception.ValidationException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TripLifecycleIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named = "M2_TEST_DB_URL", matches = ".+")
class TripLifecycleIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = TripRepository.class)
    @Import({ TripServiceImpl.class, TripEventPublisher.class, PaymentEventConsumer.class, DriverReservedConsumer.class,
            TripMapper.class })
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("M2_TEST_DB_URL"), "m2_test", "m2_test");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(source);
            f.setPackagesToScan("com.example.tripservice.entity");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.default_schema", "m2_trip",
                    "hibernate.hbm2ddl.create_namespaces", "true"));
            return f;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Bean
        PricingServiceClient pricing() {
            return mock(PricingServiceClient.class);
        }

        @Bean
        DriverServiceClient driver() {
            return mock(DriverServiceClient.class);
        }

    }

    @Autowired
    TripService trips;

    @Autowired
    TripRepository tripRows;

    @Autowired
    PricingServiceClient pricing;

    @Autowired
    DriverServiceClient drivers;

    @Autowired
    PaymentEventConsumer payments;

    @Autowired
    DriverReservedConsumer assignments;

    @Autowired
    ProcessedEventRepository processed;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    TripStatusHistoryRepository histories;

    @Autowired
    ObjectMapper mapper;

    @MockitoSpyBean
    TripStatusHistoryRepository historyFailure;

    @MockitoSpyBean
    TripEventPublisher publisherFailure;

    UUID passenger;

    @BeforeEach
    void pricing() {
        reset(pricing, drivers);
        passenger = UUID.randomUUID();
        when(pricing.getQuote(any(), any()))
            .thenReturn(new PricingQuoteResponse(2.8, 20, new BigDecimal("33500"), "VND"));
        when(pricing.getFinalFare(any(), anyDouble(), anyInt()))
            .thenAnswer(i -> new PricingFinalFareResponse(i.getArgument(0), new BigDecimal("47500"), "VND"));
    }

    UUID completedTrip() {
        var location = LocationDto.builder().latitude(21.0).longitude(105.0).build();
        var trip = trips.createTrip(CreateTripRequest.builder().pickup(location).destination(location).build(),
                UUID.randomUUID().toString(), passenger);
        UUID driver = UUID.randomUUID(), eventId = UUID.randomUUID();
        var e = EventEnvelope.builder()
            .eventId(eventId)
            .eventType("DriverReserved")
            .aggregateId(trip.getTripId().toString())
            .aggregateType("Trip")
            .producer("matching-service")
            .correlationId(trip.getCorrelationId())
            .occurredAt(Instant.now())
            .payload(Map.of("tripId", trip.getTripId(), "driverId", driver, "passengerId", passenger, "reservedUntil",
                    Instant.now().plusSeconds(30), "reservationVersion", 2))
            .build();
        assignments.handleDriverReserved(new ConsumerRecord<>("matching-events", 0, 0, trip.getTripId().toString(),
                mapper.writeValueAsString(e)));
        assignments.handleDriverReserved(new ConsumerRecord<>("matching-events", 0, 0, trip.getTripId().toString(),
                mapper.writeValueAsString(e)));
        trips.applyAssignmentOutcome(trip.getTripId(), driver, 2,
                tripRows.findById(trip.getTripId()).orElseThrow().getPendingAssignmentEventId(), true);
        trips.acceptTrip(trip.getTripId(), driver);
        trips.startTrip(trip.getTripId(), driver);
        trips.completeTrip(trip.getTripId(), driver);
        return trip.getTripId();
    }

    ConsumerRecord<String, String> payment(UUID trip, UUID eventId, boolean success, BigDecimal amount) {
        UUID paymentId = UUID.randomUUID();
        var payload = new HashMap<String, Object>(Map.of("tripId", trip, "paymentId", paymentId, "passengerId",
                passenger, "amount", amount, "currency", "VND"));
        payload.put(success ? "processedAt" : "failedAt", Instant.now());
        if (!success) {
            payload.put("reason", "SIMULATED_FAILURE");
            payload.put("retryCount", 3);
        }
        UUID completed = outbox.findAll()
            .stream()
            .filter(e -> e.getAggregateId().equals(trip.toString()) && e.getEventType().equals("TripCompleted"))
            .findFirst()
            .orElseThrow()
            .getId();
        var event = EventEnvelope.builder()
            .eventId(eventId)
            .eventType(success ? "PaymentSucceeded" : "PaymentFailed")
            .aggregateId(paymentId.toString())
            .aggregateType("Payment")
            .producer("payment-service")
            .causationId(completed.toString())
            .correlationId(trips.getTrip(trip).getCorrelationId())
            .occurredAt(Instant.now())
            .payload(payload)
            .build();
        return new ConsumerRecord<>("payment-events", 0, 0, trip.toString(), mapper.writeValueAsString(event));
    }

    @Test
    void happyPathAndReplayCommitOnePaymentTransition() {
        UUID trip = completedTrip(), eventId = UUID.randomUUID();
        var event = payment(trip, eventId, true, new BigDecimal("47500"));
        payments.onPaymentEvent(event);
        payments.onPaymentEvent(event);
        assertEquals(TripStatus.PAID, trips.getTrip(trip).getStatus());
        assertTrue(processed.existsById(eventId.toString()));
        assertEquals(1,
                histories.findAll()
                    .stream()
                    .filter(h -> h.getTripId().equals(trip) && h.getToStatus() == TripStatus.PAID)
                    .count());
        var completed = outbox.findAll()
            .stream()
            .filter(e -> e.getAggregateId().equals(trip.toString()) && e.getEventType().equals("TripCompleted"))
            .findFirst()
            .orElseThrow();
        assertEquals(8, mapper.readTree(completed.getPayload()).get("payload").size());
        assertTrue(mapper.readTree(completed.getPayload()).get("payload").get("finalFare").isNumber());
    }

    @Test
    void failedPaymentLeavesTripPaymentFailed_andCannotBeOverwritten() {
        UUID trip = completedTrip();
        payments.onPaymentEvent(payment(trip, UUID.randomUUID(), false, new BigDecimal("47500")));
        payments.onPaymentEvent(payment(trip, UUID.randomUUID(), true, new BigDecimal("47500")));
        assertEquals(TripStatus.PAYMENT_FAILED, trips.getTrip(trip).getStatus());
    }

    @Test
    void invalidAmountDoesNotCommitDedup() {
        UUID trip = completedTrip(), eventId = UUID.randomUUID();
        assertThrows(ValidationException.class,
                () -> payments.onPaymentEvent(payment(trip, eventId, true, BigDecimal.ONE)));
        assertEquals(TripStatus.COMPLETED, trips.getTrip(trip).getStatus());
        assertFalse(processed.existsById(eventId.toString()));
    }

    @Test
    void historyFailureRollsBackTripAndDedup_thenReplayRecovers() {
        UUID trip = completedTrip(), eventId = UUID.randomUUID();
        var event = payment(trip, eventId, true, new BigDecimal("47500"));
        doThrow(new IllegalStateException("history write failed")).when(historyFailure).save(any());
        assertThrows(IllegalStateException.class, () -> payments.onPaymentEvent(event));
        assertEquals(TripStatus.COMPLETED, trips.getTrip(trip).getStatus());
        assertFalse(processed.existsById(eventId.toString()));
        reset(historyFailure);
        payments.onPaymentEvent(event);
        assertEquals(TripStatus.PAID, trips.getTrip(trip).getStatus());
    }

    @Test
    void concurrentReplayStillProducesOnePaidHistory() throws Exception {
        UUID trip = completedTrip(), eventId = UUID.randomUUID();
        var event = payment(trip, eventId, true, new BigDecimal("47500"));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                start.await();
                payments.onPaymentEvent(event);
                return true;
            });
            var b = executor.submit(() -> {
                start.await();
                payments.onPaymentEvent(event);
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
            payments.onPaymentEvent(event);
            assertEquals(TripStatus.PAID, trips.getTrip(trip).getStatus());
            assertEquals(1,
                    histories.findAll()
                        .stream()
                        .filter(h -> h.getTripId().equals(trip) && h.getToStatus() == TripStatus.PAID)
                        .count());
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationCommitsBeforeRelease_andConcurrentStartCannotReviveTrip() throws Exception {
        var location = LocationDto.builder().latitude(21.0).longitude(105.0).build();
        UUID trip = trips
            .createTrip(CreateTripRequest.builder().pickup(location).destination(location).build(),
                    UUID.randomUUID().toString(), passenger)
            .getTripId();
        UUID driver = UUID.randomUUID();
        confirmAssignment(trip, driver);
        var releasing = new CountDownLatch(1);
        var allowRelease = new CountDownLatch(1);
        doAnswer(i -> {
            releasing.countDown();
            assertTrue(allowRelease.await(5, TimeUnit.SECONDS));
            return null;
        }).when(drivers).releaseDriverReservation(driver, trip);
        var executor = Executors.newFixedThreadPool(2);
        try {
            UUID owner = passenger;
            var cancel = executor.submit(() -> trips.cancelTrip(trip, null, owner));
            assertTrue(releasing.await(5, TimeUnit.SECONDS));
            assertEquals(TripStatus.CANCELLED, trips.getTrip(trip).getStatus());
            var start = executor.submit(() -> trips.startTrip(trip, driver));
            var failure = assertThrows(ExecutionException.class, () -> start.get(5, TimeUnit.SECONDS));
            assertInstanceOf(com.example.tripservice.exception.InvalidTripStateException.class, failure.getCause());
            allowRelease.countDown();
            assertEquals(TripStatus.CANCELLED, cancel.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(TripStatus.CANCELLED, trips.getTrip(trip).getStatus());
        }
        finally {
            allowRelease.countDown();
            executor.shutdownNow();
        }
    }

    UUID assignedTrip(UUID driver) {
        var location = LocationDto.builder().latitude(21.0).longitude(105.0).build();
        UUID trip = trips
            .createTrip(CreateTripRequest.builder().pickup(location).destination(location).build(),
                    UUID.randomUUID().toString(), passenger)
            .getTripId();
        confirmAssignment(trip, driver);
        return trip;
    }

    void confirmAssignment(UUID trip, UUID driver) {
        trips.beginAssignment(trip, driver, 2, Instant.parse("2026-11-09T12:00:30Z"), UUID.randomUUID());
        UUID command = tripRows.findById(trip).orElseThrow().getPendingAssignmentEventId();
        trips.applyAssignmentOutcome(trip, driver, 2, command, true);
    }

    @Test
    void cancellationOutboxFailureCannotFreeDriverOfActiveTrip() {
        UUID driver = UUID.randomUUID(), trip = assignedTrip(driver);
        doThrow(new IllegalStateException("cancel outbox failure")).when(publisherFailure)
            .publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
        assertThrows(IllegalStateException.class, () -> trips.cancelTrip(trip, null, passenger));
        assertEquals(TripStatus.ASSIGNED, trips.getTrip(trip).getStatus());
        verify(drivers, never()).releaseDriverReservation(driver, trip);
    }

    @Test
    void cancellationCommitFailureCannotFreeDriverOfActiveTrip() {
        UUID driver = UUID.randomUUID(), trip = assignedTrip(driver);
        doAnswer(i -> {
            i.callRealMethod();
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("simulated cancellation commit failure");
                    }
                });
            return null;
        }).when(publisherFailure).publish(argThat(e -> "TripCancelled".equals(e.getEventType())));
        assertThrows(IllegalStateException.class, () -> trips.cancelTrip(trip, null, passenger));
        assertEquals(TripStatus.ASSIGNED, trips.getTrip(trip).getStatus());
        verify(drivers, never()).releaseDriverReservation(driver, trip);
    }

}

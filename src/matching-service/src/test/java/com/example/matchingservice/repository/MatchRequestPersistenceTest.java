package com.example.matchingservice.repository;

import com.example.matchingservice.entity.MatchRequest;
import com.example.matchingservice.entity.MatchStatus;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "M2_TEST_DB_URL", matches = ".+")
class MatchRequestPersistenceTest {

    @Test
    void saveAssignedId_thenReloadAndUpdate_preservesOneRequest() {
        var configuration = new Configuration().addAnnotatedClass(MatchRequest.class)
            .setProperty("hibernate.connection.url", System.getenv("M2_TEST_DB_URL"))
            .setProperty("hibernate.connection.username", "m2_test")
            .setProperty("hibernate.connection.password", "m2_test")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = configuration.buildSessionFactory(); var em = factory.createEntityManager()) {
            var repository = new SimpleJpaRepository<MatchRequest, UUID>(MatchRequest.class, em);
            UUID id = UUID.randomUUID();
            var request = MatchRequest.builder()
                .requestId(id)
                .tripId(id)
                .passengerId(UUID.randomUUID())
                .correlationId(UUID.randomUUID().toString())
                .pickupLat(21.0)
                .pickupLng(105.0)
                .build();
            em.getTransaction().begin();
            repository.saveAndFlush(request);
            em.clear();
            var loaded = repository.findById(id).orElseThrow();
            loaded.setStatus(MatchStatus.NO_DRIVER_FOUND);
            repository.saveAndFlush(loaded);
            em.getTransaction().commit();
            em.clear();
            assertEquals(1, repository.count());
            assertEquals(MatchStatus.NO_DRIVER_FOUND, repository.findById(id).orElseThrow().getStatus());
        }
    }

}

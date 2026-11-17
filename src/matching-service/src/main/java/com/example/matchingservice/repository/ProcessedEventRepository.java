package com.example.matchingservice.repository;

import com.example.matchingservice.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByResultSummary(String summary);

    Optional<ProcessedEvent> findFirstByResultSummaryOrderByProcessedAtAsc(String summary);

}

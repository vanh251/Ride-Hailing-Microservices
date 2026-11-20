package com.example.driverservice.repository;

import com.example.driverservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Every pending event, oldest first, so the events of one trip are sent in the order they were written. */
    List<OutboxEvent> findAllByOrderByCreatedAtAscIdAsc();
}

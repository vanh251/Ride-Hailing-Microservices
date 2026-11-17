package com.example.tripservice.repository;

import com.example.tripservice.entity.Trip;
import com.example.tripservice.entity.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TripRepository extends JpaRepository<Trip, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Trip t where t.tripId = :id")
    java.util.Optional<Trip> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<Trip> findByPassengerId(UUID passengerId);

    List<Trip> findByDriverId(UUID driverId);

    List<Trip> findByStatus(TripStatus status);

}

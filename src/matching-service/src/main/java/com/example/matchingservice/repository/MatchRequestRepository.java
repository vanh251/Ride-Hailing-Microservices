package com.example.matchingservice.repository;

import com.example.matchingservice.entity.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, UUID> {

    Optional<MatchRequest> findByTripId(UUID tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MatchRequest r where r.requestId=:id")
    Optional<MatchRequest> findByIdForUpdate(@Param("id") UUID id);

    List<MatchRequest> findByStatusIn(Collection<MatchStatus> statuses);

}

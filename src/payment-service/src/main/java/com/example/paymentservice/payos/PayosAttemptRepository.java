package com.example.paymentservice.payos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface PayosAttemptRepository extends JpaRepository<PayosAttempt,Long> {
    List<PayosAttempt> findByPaymentIdOrderByOrderCodeDesc(UUID paymentId);
    @Query("select a from PayosAttempt a where a.state in ('NEW','UNCERTAIN','PENDING','CANCELLED','EXPIRED') order by a.lastCheckedAt asc")
    List<PayosAttempt> reconciliationBatch(Pageable page);
}

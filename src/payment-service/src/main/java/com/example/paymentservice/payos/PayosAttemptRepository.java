package com.example.paymentservice.payos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface PayosAttemptRepository extends JpaRepository<PayosAttempt,Long> {
    List<PayosAttempt> findByPaymentIdOrderByOrderCodeDesc(UUID paymentId);
    // Retired checkouts stay checked only until retiredAfter, so old abandoned QR codes do not slow down new payments
    @Query("select a from PayosAttempt a where a.state in ('NEW','UNCERTAIN','PENDING') or (a.state in ('CANCELLED','EXPIRED')"
            + " and (a.expiresAt is null or a.expiresAt > :retiredAfter)) order by a.lastCheckedAt asc")
    List<PayosAttempt> reconciliationBatch(@org.springframework.data.repository.query.Param("retiredAfter") java.time.Instant retiredAfter, Pageable page);
}

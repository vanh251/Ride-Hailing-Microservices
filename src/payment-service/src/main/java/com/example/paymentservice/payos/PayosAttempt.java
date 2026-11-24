package com.example.paymentservice.payos;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Entity
@Table(name="payos_attempts", indexes=@Index(name="idx_payos_attempt_payment", columnList="payment_id"))
@Getter @Setter @NoArgsConstructor
public class PayosAttempt {
    // payOS needs order codes unique across the whole channel, not per database: a per-database sequence from 100000
    // reused codes another database had already sent to the same channel, and the old order then blocked the payment.
    @Id @Column(name="order_code") private Long orderCode;
    @Column(name="payment_id", nullable=false) private UUID paymentId;
    @Column(name="payment_link_id", unique=true) private String paymentLinkId;
    @Column(name="checkout_url", length=2048) private String checkoutUrl;
    @Column(nullable=false, length=30) private String state = "NEW";
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="last_checked_at", nullable=false) private Instant lastCheckedAt = Instant.EPOCH;
    @Column(length=500) private String message;
    @Column(name="captured_reference") private String capturedReference;

    /** Milliseconds x 1000 + 3 random digits: grows over time (latest attempt = highest code) and stays below payOS's 2^53 limit. */
    public static long newOrderCode() {
        return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
    }
}

package com.example.paymentservice.payos;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="payos_attempts", indexes=@Index(name="idx_payos_attempt_payment", columnList="payment_id"))
@Getter @Setter @NoArgsConstructor
public class PayosAttempt {
    @Id @GeneratedValue(strategy=GenerationType.SEQUENCE, generator="payos_order")
    @SequenceGenerator(name="payos_order", sequenceName="payos_order_code_seq", allocationSize=1, initialValue=100000)
    @Column(name="order_code") private Long orderCode;
    @Column(name="payment_id", nullable=false) private UUID paymentId;
    @Column(name="payment_link_id", unique=true) private String paymentLinkId;
    @Column(name="checkout_url", length=2048) private String checkoutUrl;
    @Column(nullable=false, length=30) private String state = "NEW";
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="last_checked_at", nullable=false) private Instant lastCheckedAt = Instant.EPOCH;
    @Column(length=500) private String message;
    @Column(name="captured_reference") private String capturedReference;
}

package com.example.paymentservice.payos;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Verified provider notifications, including money requiring manual review. */
@Entity @Table(name="payos_receipts", uniqueConstraints=@UniqueConstraint(columnNames={"order_code","provider_reference"}))
@Getter @Setter @NoArgsConstructor
public class PayosReceipt {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private java.util.UUID id;
    @Column(name="order_code",nullable=false) private Long orderCode;
    @Column(name="provider_reference",nullable=false) private String providerReference;
    @Column(nullable=false) private Long amount;
    @Column(nullable=false,length=3) private String currency;
    @Column(nullable=false,columnDefinition="TEXT") private String payload;
    @Column(nullable=false) private Instant receivedAt=Instant.now();
}

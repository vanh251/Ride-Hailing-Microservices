package com.example.paymentservice.payos;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PayosReceiptRepository extends JpaRepository<PayosReceipt,UUID> {
    Optional<PayosReceipt> findByOrderCodeAndProviderReference(Long orderCode,String providerReference);
}

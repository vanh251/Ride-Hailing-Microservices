package com.example.paymentservice;

import com.example.paymentservice.payos.PayosAttempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PayosOrderCodeTest {

    @Test
    @DisplayName("newOrderCode is far from the old per-database 100000 range, below payOS's 2^53 limit, and grows over time")
    void newOrderCode_isChannelUniqueAndIncreasing() throws InterruptedException {
        long first = PayosAttempt.newOrderCode();
        Thread.sleep(2);
        long later = PayosAttempt.newOrderCode();

        assertTrue(first > 1_000_000_000_000_000L, "time based, never a small database counter");
        assertTrue(later < 9_007_199_254_740_991L, "payOS orderCode must stay a safe integer");
        assertTrue(later > first, "a renewed attempt gets a higher code, so it stays the latest");
    }
}

package com.example.matchingservice.service;

import com.example.matchingservice.repository.MatchRequestRepository;
import com.example.matchingservice.entity.MatchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingTimeoutScheduler {

    private final MatchRequestRepository requests;

    private final MatchingService service;

    @Scheduled(fixedDelayString = "${matching.retry-interval-ms:5000}")
    public void retryEligibleRequests() {
        for (var r : requests
            .findByStatusIn(List.of(MatchStatus.PENDING, MatchStatus.MATCHING, MatchStatus.NO_DRIVER_FOUND))) {
            try {
                service.retryOrExpire(r.getRequestId());
            }
            catch (RuntimeException ex) {
                log.warn("Matching retry deferred request={}", r.getRequestId(), ex);
            }
        }
    }

}

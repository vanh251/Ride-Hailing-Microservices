package com.example.matchingservice;

import com.example.matchingservice.client.DriverServiceClient;
import com.example.matchingservice.event.MatchingEventPublisher;
import com.example.matchingservice.repository.DriverOfferRepository;
import com.example.matchingservice.repository.MatchRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class MatchingServiceApplicationTests {

    @MockitoBean
    private com.example.matchingservice.repository.ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private MatchRequestRepository matchRequestRepository;

    @MockitoBean
    private DriverOfferRepository driverOfferRepository;

    @MockitoBean
    private DriverServiceClient driverServiceClient;

    @MockitoBean
    private MatchingEventPublisher matchingEventPublisher;

    @MockitoBean
    private com.example.matchingservice.repository.OutboxEventRepository outboxEventRepository;

    @Test
    void contextLoads() {
    }

}

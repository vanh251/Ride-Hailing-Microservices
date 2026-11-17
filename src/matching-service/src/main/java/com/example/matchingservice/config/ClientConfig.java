package com.example.matchingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import java.time.Clock;

@Configuration
public class ClientConfig {

    @Bean
    public RestClient.Builder internalRestClientBuilder() {
        return RestClient.builder().requestInterceptor(new RestClientCorrelationInterceptor());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

}

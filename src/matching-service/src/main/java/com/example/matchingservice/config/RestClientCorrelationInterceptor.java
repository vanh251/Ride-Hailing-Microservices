package com.example.matchingservice.config;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class RestClientCorrelationInterceptor implements ClientHttpRequestInterceptor {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private static final String CORRELATION_ID_KEY = "correlationId";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId != null) {
            request.getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        }
        return execution.execute(request, body);
    }

}

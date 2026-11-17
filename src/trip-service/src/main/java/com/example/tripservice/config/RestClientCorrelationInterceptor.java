package com.example.tripservice.config;

import org.slf4j.MDC;
import org.springframework.http.client.*;
import org.springframework.http.HttpRequest;
import java.io.IOException;

public class RestClientCorrelationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String id = MDC.get("correlationId");
        if (id != null)
            request.getHeaders().set("X-Correlation-ID", id);
        return execution.execute(request, body);
    }

}

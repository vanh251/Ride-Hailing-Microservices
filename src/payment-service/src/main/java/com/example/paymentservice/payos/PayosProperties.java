package com.example.paymentservice.payos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix="payment.payos")
public class PayosProperties {
    private String clientId = "", apiKey = "", checksumKey = "", returnUrl = "", cancelUrl = "";
    private String baseUrl = "https://api-merchant.payos.vn";
    private int timeoutMs = 5000;
    public boolean configured() {
        return !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank()
                && validCallback(returnUrl) && validCallback(cancelUrl);
    }
    private boolean validCallback(String url) {
        try {
            var uri = java.net.URI.create(url);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}

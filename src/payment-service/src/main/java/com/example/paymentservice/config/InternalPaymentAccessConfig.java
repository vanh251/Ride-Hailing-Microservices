package com.example.paymentservice.config;

import com.example.paymentservice.controller.PaymentController;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Match the resolved controller handler so decoded paths and matrix parameters cannot bypass auth. */
@Configuration
public class InternalPaymentAccessConfig implements WebMvcConfigurer {
    private final byte[] key;
    public InternalPaymentAccessConfig(@Value("${internal.api-key:local-dev-internal-api-key}") String key) {
        this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
                if (handler instanceof HandlerMethod method && PaymentController.class.isAssignableFrom(method.getBeanType())
                        && Set.of("chargePayment","refundPayment").contains(method.getMethod().getName())) {
                    String supplied=request.getHeader("X-Internal-Api-Key");
                    if (key.length==0 || supplied==null || !MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8))) {
                        response.setStatus(403); response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"INTERNAL_ACCESS_REQUIRED\",\"message\":\"Internal service key required\"}");
                        return false;
                    }
                }
                return true;
            }
        });
    }
}

package com.example.paymentservice.payos;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/payments")
public class PayosController {
    private final CheckoutIdentity identity;
    private final PayosCheckoutService service;
    @PostMapping("/{tripId}/checkout")
    public CheckoutResponse create(@PathVariable UUID tripId,@RequestHeader(value="Authorization",required=false) String bearer) {
        var user=identity.verify(bearer); return service.checkout(tripId,user.id(),user.role(),true);
    }
    @GetMapping("/{tripId}/checkout")
    public CheckoutResponse read(@PathVariable UUID tripId,@RequestHeader(value="Authorization",required=false) String bearer) {
        var user=identity.verify(bearer); return service.checkout(tripId,user.id(),user.role(),false);
    }
    @PostMapping("/webhooks/payos")
    public Map<String,String> webhook(@RequestBody String raw) {
        service.webhook(raw); return Map.of("code","00","desc","ok");
    }
}

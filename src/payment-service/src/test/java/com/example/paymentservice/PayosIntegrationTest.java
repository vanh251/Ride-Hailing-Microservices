package com.example.paymentservice;

import com.example.paymentservice.payos.*;
import com.example.paymentservice.entity.*;
import com.example.paymentservice.repository.*;
import com.example.paymentservice.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import vn.payos.PayOS;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payment.outbox.poll-interval-ms=3600000", "payment.payos.reconcile-interval-ms=3600000"})
class PayosIntegrationTest {
    static final UUID OWNER = UUID.randomUUID(), DRIVER = UUID.randomUUID();
    static final HttpServer HTTP;
    static final Map<Long, String> orders = new ConcurrentHashMap<>();
    static final AtomicInteger creates = new AtomicInteger();
    static volatile String tripStatus = "COMPLETED", provider = "PAYOS", fare = "47500", remoteStatus="PENDING";
    static volatile boolean uncertain;
    static volatile String sentDescription;
    static volatile String missingOrderCode = "101";
    static volatile long paid;
    static {
        try {
            HTTP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HTTP.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String result;
                if (path.startsWith("/api/trips/")) {
                    if (!"internal-test".equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
                        exchange.sendResponseHeaders(403, -1); exchange.close(); return;
                    }
                    result = "{\"tripId\":\"" + path.substring(11) + "\",\"passengerId\":\""+OWNER+"\",\"driverId\":\""+DRIVER+"\",\"status\":\""+tripStatus+"\",\"finalFare\":"+fare+",\"currency\":\"VND\",\"paymentProvider\":\""+provider+"\"}";
                } else if (exchange.getRequestMethod().equals("POST")) {
                    var body = new JsonMapper().readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    long code = body.path("orderCode").asLong();
                    sentDescription=body.path("description").asText();
                    creates.incrementAndGet(); orders.put(code, "link-"+code);
                    if (uncertain) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                    result = "{\"code\":\"00\",\"desc\":\"ok\",\"data\":{\"orderCode\":"+code+",\"amount\":47500,\"currency\":\"VND\",\"paymentLinkId\":\"link-"+code+"\",\"status\":\"PENDING\",\"checkoutUrl\":\"https://pay.payos.vn/web/link-"+code+"\",\"qrCode\":\"qr\",\"bin\":\"970422\",\"accountNumber\":\"123\",\"accountName\":\"test\",\"description\":\"trip\"}}";
                } else {
                    long code = Long.parseLong(path.substring(path.lastIndexOf('/')+1));
                    result = orders.containsKey(code) ? "{\"code\":\"00\",\"desc\":\"ok\",\"data\":{\"id\":\"link-"+code+"\",\"orderCode\":"+code+",\"amount\":47500,\"amountPaid\":"+paid+",\"amountRemaining\":"+(47500-paid)+",\"status\":\""+(paid == 47500 ? "PAID" : remoteStatus)+"\",\"createdAt\":\"2026-11-24\",\"transactions\":[]}}" : "{\"code\":\""+missingOrderCode+"\",\"desc\":\"provider error\",\"data\":null}";
                }
                if (!path.startsWith("/api/trips/")) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String,Object> envelope = mapper.readValue(result, Map.class);
                    if ("00".equals(envelope.get("code"))) {
                        envelope.put("signature", new PayOS("test","test","test-checksum").getCrypto()
                                .createSignatureFromObj(envelope.get("data"),"test-checksum"));
                        result=mapper.writeValueAsString(envelope);
                    }
                }
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            HTTP.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + HTTP.getAddress().getPort();
        r.add("trip-service.url", () -> base); r.add("internal.api-key", () -> "internal-test");
        r.add("payment.payos.base-url", () -> base);
        r.add("payment.payos.client-id", () -> "test"); r.add("payment.payos.api-key", () -> "test");
        r.add("payment.payos.checksum-key", () -> "test-checksum");
        r.add("payment.payos.return-url", () -> "https://app.example/rider.html");
        r.add("payment.payos.cancel-url", () -> "https://app.example/rider.html");
    }
    @Autowired PayosCheckoutService checkout;
    @Autowired PaymentRepository payments;
    @Autowired PayosAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledger;
    @Autowired OutboxEventRepository outbox;
    @Autowired PayosProperties config;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.paymentservice.kafka.producer.PaymentEventProducer events;
    @BeforeEach void reset() { creates.set(0); orders.clear(); paid=0; uncertain=false; tripStatus="COMPLETED"; provider="PAYOS"; fare="47500"; remoteStatus="PENDING"; missingOrderCode="101"; org.mockito.Mockito.reset(events); }

    @Test void firstCheckoutAcceptsLiveMissingOrderCode101() {
        var response=checkout.checkout(UUID.randomUUID(),OWNER,"PASSENGER",true);
        assertEquals("PENDING",response.status());
        assertEquals(1,creates.get());
    }

    @Test void invalidChannelIsNotTreatedAsAnAbsentOrder() {
        missingOrderCode="214";
        var failure=assertThrows(BusinessException.class,()->checkout.checkout(UUID.randomUUID(),OWNER,"PASSENGER",true));
        assertEquals(503,failure.getStatus().value());
        assertEquals(0,creates.get());
    }

    @Test void canonicalFareOwnershipAndProviderAreEnforced() {
        UUID trip = UUID.randomUUID();
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, UUID.randomUUID(), "PASSENGER", true));
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, DRIVER, "DRIVER", true));
        tripStatus="IN_PROGRESS";
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, OWNER, "PASSENGER", true));
        tripStatus="COMPLETED"; fare="47.5";
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, OWNER, "PASSENGER", true));
        fare="47500"; provider="MOCK";
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, OWNER, "PASSENGER", true));
        provider="PAYOS";
        var response = checkout.checkout(trip, OWNER, "PASSENGER", true);
        assertEquals(47500, response.amount().longValueExact());
        assertEquals("PENDING", checkout.checkout(trip, DRIVER, "DRIVER", false).status());
        assertEquals(1, creates.get());
        assertTrue(sentDescription.length()<=9,"Bank transfer description must fit every payOS account");
    }
    @Test void concurrentCreationAndUncertainRetryReuseOneDurableOrder() throws Exception {
        UUID trip = UUID.randomUUID(); uncertain=true;
        assertThrows(BusinessException.class, () -> checkout.checkout(trip, OWNER, "PASSENGER", true));
        assertEquals(1, creates.get()); assertEquals(1, attempts.findByPaymentIdOrderByOrderCodeDesc(payments.findByIdempotencyKey("payos-"+trip).orElseThrow().getPaymentId()).size());
        uncertain=false;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var results = pool.invokeAll(Collections.nCopies(4, (Callable<CheckoutResponse>) () -> checkout.checkout(trip, OWNER, "PASSENGER", true)));
            Set<String> links = new HashSet<>(); for (var result : results) links.add(result.get().checkoutUrl());
            assertEquals(1, links.size()); assertFalse(links.contains(null)); assertEquals(1, creates.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void signedWebhookCapturesOnceAndRejectsFakeOrMismatchedMoney() {
        UUID trip = UUID.randomUUID(); var response=checkout.checkout(trip, OWNER, "PASSENGER", true);
        var attempt=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        long beforeLedger=ledger.count(), beforeOutbox=outbox.count();
        assertThrows(BusinessException.class, () -> checkout.webhook("{\"data\":{},\"signature\":\"bad\"}"));
        assertThrows(BusinessException.class, () -> checkout.webhook(webhook(attempt.getOrderCode(), 1, "wrong")));
        assertEquals(beforeLedger, ledger.count());
        // Mismatched link is rejected without poisoning the valid attempt.
        checkout.webhook(webhook(attempt.getOrderCode(), 47500, attempt.getPaymentLinkId()));
        checkout.webhook(webhook(attempt.getOrderCode(), 47500, attempt.getPaymentLinkId()));
        assertEquals("SUCCESS", checkout.checkout(trip, OWNER, "PASSENGER", true).status());
        assertEquals(beforeLedger+1, ledger.count()); assertEquals(beforeOutbox+1, outbox.count());
        assertEquals(1, creates.get());
        checkout.webhook(webhook(999999999L, 47500, "probe"));
        assertEquals(beforeLedger+1, ledger.count());
    }
    @Test void reconciliationRecoversMissedWebhookAndPartialMoneyNeedsReview() {
        UUID trip=UUID.randomUUID(); checkout.checkout(trip, OWNER, "PASSENGER", true); paid=47500;
        checkout.reconcile();
        assertEquals("SUCCESS", checkout.checkout(trip, OWNER, "PASSENGER", false).status());
        UUID partial=UUID.randomUUID(); paid=0; checkout.checkout(partial, OWNER, "PASSENGER", true); paid=100;
        long captures=ledger.count(); checkout.reconcile();
        assertEquals("REVIEW_REQUIRED", checkout.checkout(partial, OWNER, "PASSENGER", false).status());
        assertEquals(captures, ledger.count());
    }
    @Test void readingCheckoutConfirmsPaidQrWithoutAButton() {
        UUID trip=UUID.randomUUID(); var created=checkout.checkout(trip, OWNER, "PASSENGER", true); paid=47500;
        // Just checked: the page's next poll does not call payOS again yet
        assertEquals("PENDING", checkout.checkout(trip, OWNER, "PASSENGER", false).status());
        var attempt=attempts.findByPaymentIdOrderByOrderCodeDesc(created.paymentId()).get(0);
        attempt.setLastCheckedAt(java.time.Instant.EPOCH); attempts.saveAndFlush(attempt);
        assertEquals("SUCCESS", checkout.checkout(trip, DRIVER, "DRIVER", false).status());
        assertEquals(1, creates.get());
    }
    @Test void longRetiredCheckoutsLeaveTheReconciliationBatch() {
        UUID trip=UUID.randomUUID(); var created=checkout.checkout(trip, OWNER, "PASSENGER", true);
        var attempt=attempts.findByPaymentIdOrderByOrderCodeDesc(created.paymentId()).get(0);
        attempt.setState("EXPIRED"); attempt.setExpiresAt(java.time.Instant.now().minus(java.time.Duration.ofDays(2))); attempts.saveAndFlush(attempt);
        var batch=attempts.reconciliationBatch(java.time.Instant.now().minus(java.time.Duration.ofDays(1)), org.springframework.data.domain.PageRequest.of(0,1000));
        assertTrue(batch.stream().noneMatch(a -> a.getOrderCode().equals(attempt.getOrderCode())));
    }
    @Test void cancelledLinkCanRenewButMoneyOnRetiredOrderRequiresReview() {
        UUID trip=UUID.randomUUID(); var first=checkout.checkout(trip,OWNER,"PASSENGER",true);
        var retired=attempts.findByPaymentIdOrderByOrderCodeDesc(first.paymentId()).get(0);
        long captures=ledger.count(), notifications=outbox.count(); remoteStatus="CANCELLED";
        checkout.checkout(trip,OWNER,"PASSENGER",true);
        assertEquals(2,creates.get()); assertEquals(notifications,outbox.count());
        checkout.webhook(webhook(retired.getOrderCode(),47500,retired.getPaymentLinkId()));
        assertEquals("REVIEW_REQUIRED",checkout.checkout(trip,OWNER,"PASSENGER",false).status());
        assertEquals(captures,ledger.count()); assertEquals(notifications,outbox.count());
    }
    @Test void changedCanonicalFareCannotReuseAnOldAmount() {
        UUID trip=UUID.randomUUID(); checkout.checkout(trip,OWNER,"PASSENGER",true); fare="48000";
        assertEquals("REVIEW_REQUIRED",checkout.checkout(trip,OWNER,"PASSENGER",true).status());
        assertEquals(1,creates.get());
    }
    @Test void absentCredentialsNeverFakeSuccessAndGetRemainsPending() {
        UUID trip=UUID.randomUUID(); String clientId=config.getClientId(); config.setClientId("");
        try {
            var failure=assertThrows(BusinessException.class,()->checkout.checkout(trip,OWNER,"PASSENGER",true));
            assertEquals(503,failure.getStatus().value());
            assertEquals("PENDING",checkout.checkout(trip,OWNER,"PASSENGER",false).status()); assertEquals(0,creates.get());
        } finally { config.setClientId(clientId); }
    }
    @Test void ledgerCaptureAndOutboxRollBackTogetherWhenOutboxFails() {
        UUID trip=UUID.randomUUID(); var response=checkout.checkout(trip,OWNER,"PASSENGER",true);
        var attempt=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        long captures=ledger.count();
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox failure"))
                .when(events).publishPaymentSucceeded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThrows(IllegalStateException.class,()->checkout.webhook(webhook(attempt.getOrderCode(),47500,attempt.getPaymentLinkId())));
        assertEquals(captures,ledger.count());
        assertEquals(PaymentStatus.PENDING,payments.findById(response.paymentId()).orElseThrow().getStatus());
    }
    @Test void directServiceChargeRequiresInternalKeyAndCheckoutRequiresSignedBearer() throws Exception {
        var http=java.net.http.HttpClient.newHttpClient(); UUID trip=UUID.randomUUID(); long captures=ledger.count();
        String body="{\"passengerId\":\""+OWNER+"\",\"amount\":47500,\"currency\":\"VND\"}";
        var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/payments/"+trip+"/charge"))
                .header("Content-Type","application/json").header("X-Idempotency-Key","attack-"+trip)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403,http.send(req,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var checkoutReq=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/payments/"+trip+"/checkout"))
                .header("X-User-Id",OWNER.toString()).header("X-User-Role","PASSENGER").GET().build();
        assertEquals(401,http.send(checkoutReq,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(captures,ledger.count());
    }
    @Test void retiredAttemptMoneyAfterNewerSuccessfulCaptureIsVisibleReview() {
        UUID trip=UUID.randomUUID(); var response=checkout.checkout(trip,OWNER,"PASSENGER",true);
        var retired=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        remoteStatus="CANCELLED"; checkout.checkout(trip,OWNER,"PASSENGER",true);
        var current=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        checkout.webhook(webhook(current.getOrderCode(),47500,current.getPaymentLinkId()));
        long captures=ledger.count(),notifications=outbox.count();
        checkout.webhook(webhook(retired.getOrderCode(),47500,retired.getPaymentLinkId()));
        assertEquals("REVIEW_REQUIRED",checkout.checkout(trip,OWNER,"PASSENGER",false).status());
        assertEquals(captures,ledger.count()); assertEquals(notifications,outbox.count());
    }
    @Test void legacyInternalGuardCannotBeBypassedWithPathParameters() throws Exception {
        var http=java.net.http.HttpClient.newHttpClient();
        for (String suffix:List.of("charge;x=1","refund;x=1","%63harge")) {
            UUID trip=UUID.randomUUID();
            String body="{\"passengerId\":\""+OWNER+"\",\"amount\":47500,\"currency\":\"VND\"}";
            var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/payments/"+trip+"/"+suffix))
                    .header("Content-Type","application/json").header("X-Idempotency-Key","bypass-"+trip)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(403,http.send(req,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode(),suffix);
        }
    }
    @Test void retiredOrderIsReconciledAgainImmediatelyBeforeRenewing() {
        UUID trip=UUID.randomUUID(); var response=checkout.checkout(trip,OWNER,"PASSENGER",true);
        var retired=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        retired.setState("EXPIRED"); attempts.saveAndFlush(retired);
        paid=47500;
        assertEquals("REVIEW_REQUIRED",checkout.checkout(trip,OWNER,"PASSENGER",true).status());
        assertEquals(1,creates.get());
    }
    @Test void extraMoneyAfterReconciliationCaptureRequiresReview() {
        UUID trip=UUID.randomUUID(); var response=checkout.checkout(trip,OWNER,"PASSENGER",true);
        var a=attempts.findByPaymentIdOrderByOrderCodeDesc(response.paymentId()).get(0);
        paid=47500; checkout.reconcile(); long captures=ledger.count(),notifications=outbox.count();
        paid=95000; checkout.webhook(webhook(a.getOrderCode(),47500,a.getPaymentLinkId()));
        assertEquals("REVIEW_REQUIRED",checkout.checkout(trip,OWNER,"PASSENGER",false).status());
        assertEquals(captures,ledger.count()); assertEquals(notifications,outbox.count());
    }
    static String webhook(long order, long amount, String link) {
        Map<String,Object> data = new TreeMap<>();
        data.put("orderCode",order); data.put("amount",amount); data.put("description","trip"); data.put("accountNumber","123");
        data.put("reference","ref-"+order); data.put("transactionDateTime","2026-11-24 10:00:00"); data.put("currency","VND");
        data.put("paymentLinkId",link); data.put("code","00"); data.put("desc","ok");
        String signature=new PayOS("test","test","test-checksum").getCrypto().createSignatureFromObj(data,"test-checksum");
        return new JsonMapper().writeValueAsString(Map.of("code","00","success",true,"data",data,"signature",signature));
    }
}

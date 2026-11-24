package com.example.paymentservice.payos;

import com.example.paymentservice.entity.*;
import com.example.paymentservice.exception.BusinessException;
import com.example.paymentservice.kafka.producer.PaymentEventProducer;
import com.example.paymentservice.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.exception.APIException;
import vn.payos.model.v2.paymentRequests.*;
import vn.payos.model.webhooks.WebhookData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class PayosCheckoutService {
    private static final String CONFIG_MESSAGE="payOS checkout unavailable: configure PAYOS_CLIENT_ID, PAYOS_API_KEY, PAYOS_CHECKSUM_KEY and server return/cancel URLs";
    private final PaymentRepository payments;
    private final PayosAttemptRepository attempts;
    private final PayosReceiptRepository receipts;
    private final LedgerEntryRepository ledger;
    private final PaymentEventProducer events;
    private final TripPaymentClient trips;
    private final PayosProperties config;
    private final TransactionTemplate tx;
    private volatile PayOS client;
    public PayosCheckoutService(PaymentRepository payments,PayosAttemptRepository attempts,PayosReceiptRepository receipts,
            LedgerEntryRepository ledger,PaymentEventProducer events,TripPaymentClient trips,PayosProperties config,
            PlatformTransactionManager manager) {
        this.payments=payments; this.attempts=attempts; this.receipts=receipts; this.ledger=ledger;
        this.events=events; this.trips=trips; this.config=config; tx=new TransactionTemplate(manager);
    }
    private synchronized PayOS sdk() {
        if (!config.configured()) throw unavailable(CONFIG_MESSAGE);
        if (client==null) client=new PayOS(ClientOptions.builder().clientId(config.getClientId()).apiKey(config.getApiKey())
                .checksumKey(config.getChecksumKey()).baseURL(config.getBaseUrl()).timeoutMs(config.getTimeoutMs())
                // A create retry must first reconcile the persisted order, never retry blindly inside the SDK.
                .maxRetries(0).logLevel(ClientOptions.LogLevel.NONE).build());
        return client;
    }
    public CheckoutResponse checkout(UUID tripId,UUID caller,String role,boolean create) {
        var trip=trips.get(tripId);
        boolean owner="PASSENGER".equals(role) && trip.passengerId().equals(caller);
        boolean driver="DRIVER".equals(role) && Objects.equals(trip.driverId(),caller);
        if (!(owner || (!create && driver))) throw error("Trip access denied",HttpStatus.FORBIDDEN,"FORBIDDEN");
        validate(trip);
        Payment payment=ensure(trip);
        if (payment.getStatus()==PaymentStatus.SUCCESS || payment.getStatus()==PaymentStatus.REVIEW_REQUIRED)
            return response(payment,latest(payment.getPaymentId()));
        if (!"COMPLETED".equals(trip.status())) {
            if (create) throw error("Checkout is available only after trip completion",HttpStatus.CONFLICT,"TRIP_NOT_COMPLETED");
            return response(payment,latest(payment.getPaymentId()));
        }
        if (!create) return response(payment,latest(payment.getPaymentId()));
        sdk();
        // First transaction allocates and COMMITs the sequence order before any provider call.
        prepare(payment.getPaymentId(),false);
        Outcome outcome=process(payment.getPaymentId(),true);
        if (outcome.closed()) {
            prepare(payment.getPaymentId(),true);
            outcome=process(payment.getPaymentId(),true);
        }
        if (outcome.unavailable()) throw unavailable("payOS temporarily unavailable; saved order will be reconciled before retry");
        return outcome.response();
    }
    public void pendingFromCompletedTrip(UUID tripId) {
        var trip=trips.get(tripId); validate(trip);
        if (!Set.of("COMPLETED","PAID").contains(trip.status())) throw error("Trip not completed",HttpStatus.CONFLICT,"TRIP_NOT_COMPLETED");
        ensure(trip);
    }
    private void validate(TripPaymentClient.Trip trip) {
        if (!"PAYOS".equals(trip.paymentProvider())) throw error("Trip does not use payOS",HttpStatus.CONFLICT,"WRONG_PAYMENT_PROVIDER");
        if (!"VND".equals(trip.currency()) || trip.finalFare()==null || trip.finalFare().signum()<=0)
            throw error("A positive canonical VND final fare is required",HttpStatus.CONFLICT,"INVALID_FINAL_FARE");
        try { trip.finalFare().longValueExact(); }
        catch (ArithmeticException e) { throw error("VND final fare must be an integer",HttpStatus.CONFLICT,"INVALID_FINAL_FARE"); }
    }
    private Payment ensure(TripPaymentClient.Trip trip) {
        String key="payos-"+trip.tripId();
        try {
            return tx.execute(s -> {
                var existing=payments.findByIdempotencyKey(key);
                if (existing.isPresent()) {
                    Payment p=payments.lockById(existing.get().getPaymentId()).orElseThrow();
                    if (p.getAmount().compareTo(trip.finalFare())!=0 || !p.getPassengerId().equals(trip.passengerId())) {
                        var a=latest(p.getPaymentId());
                        if (a!=null) { review(p,a,"Canonical fare or passenger changed after obligation creation"); attempts.save(a); }
                        else p.setStatus(PaymentStatus.REVIEW_REQUIRED);
                    }
                    return p;
                }
                // Do not invent a new obligation before completion or for a paid trip with missing payment history.
                if (!"COMPLETED".equals(trip.status())) throw error("Trip not completed or payment history unavailable",HttpStatus.CONFLICT,"TRIP_NOT_COMPLETED");
                if (payments.existsByTripIdAndStatus(trip.tripId(),PaymentStatus.SUCCESS))
                    throw error("Trip already has a captured payment",HttpStatus.CONFLICT,"PAYMENT_ALREADY_CAPTURED");
                return payments.saveAndFlush(Payment.builder().paymentId(UUID.randomUUID()).tripId(trip.tripId())
                        .passengerId(trip.passengerId()).amount(trip.finalFare()).currency(trip.currency()).paymentMethod("PAYOS")
                        .status(PaymentStatus.PENDING).idempotencyKey(key).correlationId(UUID.randomUUID().toString()).build());
            });
        } catch (DataIntegrityViolationException e) {
            // Another request won the unique canonical obligation insert; its committed row is the authority.
            return payments.findByIdempotencyKey(key).orElseThrow(() -> e);
        }
    }
    private PayosAttempt latest(UUID paymentId) {
        return attempts.findByPaymentIdOrderByOrderCodeDesc(paymentId).stream().findFirst().orElse(null);
    }
    private boolean closed(PayosAttempt a) { return a!=null && Set.of("EXPIRED","CANCELLED").contains(a.getState()); }
    private void prepare(UUID paymentId,boolean renewConfirmedClosed) {
        tx.executeWithoutResult(s -> {
            Payment payment=payments.lockById(paymentId).orElseThrow();
            if (payment.getStatus()!=PaymentStatus.PENDING) return;
            var last=latest(paymentId);
            if (last==null || (renewConfirmedClosed && closed(last))) {
                var attempt=new PayosAttempt(); attempt.setPaymentId(paymentId);
                attempts.saveAndFlush(attempt);
            }
        });
    }
    private record Outcome(CheckoutResponse response,boolean unavailable,boolean closed) {}
    private Outcome process(UUID paymentId,boolean allowCreate) {
        return tx.execute(s -> {
            Payment payment=payments.lockById(paymentId).orElseThrow();
            PayosAttempt attempt=latest(paymentId);
            if (payment.getStatus()!=PaymentStatus.PENDING || attempt==null || "REVIEW_REQUIRED".equals(attempt.getState()))
                return new Outcome(response(payment,attempt),false,false);
            boolean outage=false;
            try {
                PaymentLink link=null;
                try { link=sdk().paymentRequests().get(attempt.getOrderCode()); }
                catch (APIException e) {
                    // Live payOS returns 101 for an unknown payment order. Other
                    // failures (including credentials/channel errors) must never start a create.
                    boolean absent=e.getErrorCode().filter("101"::equals).isPresent();
                    // Only an order that has NEVER had a confirmed link can be recreated, with its same code.
                    if (!absent || !allowCreate || attempt.getPaymentLinkId()!=null) throw e;
                }
                if (link!=null) applyLink(payment,attempt,link);
                else {
                    attempt.setExpiresAt(Instant.now().plusSeconds(900));
                    var created=sdk().paymentRequests().create(CreatePaymentLinkRequest.builder()
                            .orderCode(attempt.getOrderCode()).amount(payment.getAmount().longValueExact()).description("Trip fare")
                            .returnUrl(callback(config.getReturnUrl(),payment.getTripId())).cancelUrl(callback(config.getCancelUrl(),payment.getTripId()))
                            .expiredAt(attempt.getExpiresAt().getEpochSecond()).build());
                    if (!attempt.getOrderCode().equals(created.getOrderCode()) || !"VND".equals(created.getCurrency())
                            || created.getAmount()!=payment.getAmount().longValueExact() || created.getPaymentLinkId()==null) {
                        review(payment,attempt,"Provider create response did not match canonical order");
                    } else {
                        attempt.setPaymentLinkId(created.getPaymentLinkId()); attempt.setCheckoutUrl(created.getCheckoutUrl());
                        attempt.setState("PENDING"); attempt.setMessage(null);
                        if (created.getExpiredAt()!=null) attempt.setExpiresAt(Instant.ofEpochSecond(created.getExpiredAt()));
                        if (created.getStatus()!=PaymentLinkStatus.PENDING) applyLink(payment,attempt,sdk().paymentRequests().get(attempt.getOrderCode()));
                    }
                }
            } catch (vn.payos.exception.PayOSException e) {
                // Commit uncertainty, never an event which would permanently fail the completed trip.
                outage=true;
                if (attempt.getPaymentLinkId()==null) attempt.setState("UNCERTAIN");
                attempt.setMessage("Provider unavailable; reconciliation pending");
            }
            attempt.setLastCheckedAt(Instant.now()); attempts.save(attempt);
            return new Outcome(response(payment,attempt),outage,!outage && closed(attempt));
        });
    }
    private void applyLink(Payment payment,PayosAttempt a,PaymentLink link) {
        if (!a.getOrderCode().equals(link.getOrderCode()) || link.getAmount()!=payment.getAmount().longValueExact()
                || (a.getPaymentLinkId()!=null && !a.getPaymentLinkId().equals(link.getId()))) {
            review(payment,a,"Provider reconciliation mismatch"); return;
        }
        a.setPaymentLinkId(link.getId());
        // payOS get does not return a checkout URL; this is its documented hosted checkout path.
        if (a.getCheckoutUrl()==null) a.setCheckoutUrl("https://pay.payos.vn/web/"+link.getId());
        long received=link.getAmountPaid();
        if (received!=0 && received!=payment.getAmount().longValueExact()) {
            review(payment,a,"Partial or excess money reported: "+received+" VND"); return;
        }
        if (link.getStatus()==PaymentLinkStatus.PAID && received==payment.getAmount().longValueExact()) {
            capture(payment,a,null); return;
        }
        if (received>0) { review(payment,a,"Money received on non-paid checkout: "+received+" VND"); return; }
        a.setState(switch(link.getStatus()) {
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case PENDING, PROCESSING -> "PENDING";
            default -> "REVIEW_REQUIRED";
        });
        if ("REVIEW_REQUIRED".equals(a.getState())) review(payment,a,"Unexpected provider state: "+link.getStatus());
        else a.setMessage(null);
    }
    private void review(Payment p,PayosAttempt a,String reason) {
        a.setState("REVIEW_REQUIRED"); a.setMessage(reason);
        // Keep an existing SUCCESS capture intact, while surfacing review across every attempt of the obligation.
        p.setFailureReason(reason);
        if (p.getStatus()!=PaymentStatus.SUCCESS) p.setStatus(PaymentStatus.REVIEW_REQUIRED);
    }
    private void capture(Payment payment,PayosAttempt a,String reference) {
        if (closed(a) || !a.getOrderCode().equals(latest(payment.getPaymentId()).getOrderCode())) {
            review(payment,a,"Money received on a retired checkout; manual review required"); return;
        }
        if (payment.getStatus()==PaymentStatus.SUCCESS) {
            // A reconciliation capture may not have an individual reference. Check its aggregate before
            // accepting the first later webhook, which could be an additional full transfer.
            if (reference!=null && a.getCapturedReference()==null) {
                var remote=sdk().paymentRequests().get(a.getOrderCode());
                if (!remote.getId().equals(a.getPaymentLinkId()) || remote.getAmountPaid()!=payment.getAmount().longValueExact()) {
                    review(payment,a,"Additional or inconsistent money after reconciliation capture: "+remote.getAmountPaid()+" VND");
                    return;
                }
            }
            if (reference!=null && a.getCapturedReference()!=null && !reference.equals(a.getCapturedReference()))
                review(payment,a,"Additional provider transfer after capture: "+reference);
            else if (reference!=null) a.setCapturedReference(reference);
            return;
        }
        if (payment.getStatus()!=PaymentStatus.PENDING || !a.getOrderCode().equals(latest(payment.getPaymentId()).getOrderCode())
                || payments.existsByTripIdAndStatus(payment.getTripId(),PaymentStatus.SUCCESS)) {
            review(payment,a,"Late money or trip already captured; manual review required"); return;
        }
        // Re-read canonical state at capture: cancelled/changed trips cannot be silently credited.
        var trip=trips.get(payment.getTripId());
        if (!"COMPLETED".equals(trip.status()) || !"PAYOS".equals(trip.paymentProvider())
                || !Objects.equals(trip.passengerId(),payment.getPassengerId()) || !"VND".equals(trip.currency())
                || trip.finalFare()==null || trip.finalFare().compareTo(payment.getAmount())!=0) {
            review(payment,a,"Canonical trip changed before payment capture"); return;
        }
        payment.setStatus(PaymentStatus.SUCCESS); payment.setCapturedAt(Instant.now()); payment.setGatewayRef(a.getPaymentLinkId());
        a.setState("SUCCESS"); a.setCapturedReference(reference); a.setMessage(null);
        payments.saveAndFlush(payment);
        ledger.save(LedgerEntry.builder().ledgerId(UUID.randomUUID()).payment(payment).tripId(payment.getTripId())
                .entryType(LedgerEntryType.CAPTURE).amount(payment.getAmount()).currency(payment.getCurrency())
                .description("payOS capture order "+a.getOrderCode()).build());
        events.publishPaymentSucceeded(payment,null);
    }
    public void webhook(String raw) {
        WebhookData data;
        try { data=sdk().webhooks().verify(raw); }
        catch (BusinessException e) { throw e; }
        catch (Exception e) { throw error("Invalid payOS webhook signature or data",HttpStatus.BAD_REQUEST,"INVALID_WEBHOOK"); }
        if (!"00".equals(data.getCode())) throw error("Webhook is not a successful payment",HttpStatus.BAD_REQUEST,"INVALID_WEBHOOK");
        var found=attempts.findById(data.getOrderCode());
        if (found.isEmpty()) return; // Signed provider probes/unknown test orders never create money records.
        boolean accepted=Boolean.TRUE.equals(tx.execute(s -> {
            Payment p=payments.lockById(found.get().getPaymentId()).orElseThrow();
            PayosAttempt a=attempts.findById(data.getOrderCode()).orElseThrow();
            if (a.getPaymentLinkId()!=null && !a.getPaymentLinkId().equals(data.getPaymentLinkId())) return false;
            // A timed-out create may deliver its webhook before we have seen the link ID. Reconcile identity first.
            if (a.getPaymentLinkId()==null) {
                PaymentLink remote=sdk().paymentRequests().get(a.getOrderCode());
                if (!remote.getId().equals(data.getPaymentLinkId()) || !remote.getOrderCode().equals(a.getOrderCode())) return false;
                a.setPaymentLinkId(remote.getId());
            }
            var duplicate=receipts.findByOrderCodeAndProviderReference(a.getOrderCode(),data.getReference());
            if (duplicate.isPresent()) {
                if (!duplicate.get().getAmount().equals(data.getAmount()) || !duplicate.get().getCurrency().equals(data.getCurrency())) {
                    review(p,a,"Conflicting repeated provider reference"); attempts.save(a); return false;
                }
                return true;
            }
            var receipt=new PayosReceipt(); receipt.setOrderCode(a.getOrderCode()); receipt.setProviderReference(data.getReference());
            receipt.setAmount(data.getAmount()); receipt.setCurrency(data.getCurrency()); receipt.setPayload(raw); receipts.save(receipt);
            if (!"VND".equals(data.getCurrency()) || data.getAmount()!=p.getAmount().longValueExact()) {
                review(p,a,"Webhook money mismatch: "+data.getAmount()+" "+data.getCurrency()); attempts.save(a); return false;
            }
            capture(p,a,data.getReference()); attempts.save(a); return true;
        }));
        if (!accepted) throw error("Webhook payment details do not match; manual review may be required",HttpStatus.BAD_REQUEST,"WEBHOOK_MISMATCH");
    }
    @Scheduled(fixedDelayString="${payment.payos.reconcile-interval-ms:30000}")
    public void reconcile() {
        if (!config.configured()) return;
        // Bounded oldest-first polling also revisits retired attempts so late money is not lost.
        for (var a:attempts.reconciliationBatch(PageRequest.of(0,20))) {
            try { reconcileAttempt(a); } catch (Exception ignored) { /* Next bounded poll retries; never log provider secrets. */ }
        }
    }
    private void reconcileAttempt(PayosAttempt target) {
        tx.executeWithoutResult(s -> {
            var payment=payments.lockById(target.getPaymentId()).orElseThrow();
            var a=attempts.findById(target.getOrderCode()).orElseThrow();
            try { applyLink(payment,a,sdk().paymentRequests().get(a.getOrderCode())); }
            catch (vn.payos.exception.PayOSException e) { a.setMessage("Provider unavailable; reconciliation pending"); }
            a.setLastCheckedAt(Instant.now()); attempts.save(a);
        });
    }
    private CheckoutResponse response(Payment payment,PayosAttempt a) {
        String status=payment.getStatus().name();
        if (a!=null && payment.getStatus()==PaymentStatus.PENDING) status=a.getState();
        if (a!=null && "REVIEW_REQUIRED".equals(a.getState())) status="REVIEW_REQUIRED";
        if (payment.getFailureReason()!=null) status="REVIEW_REQUIRED";
        if (Set.of("NEW","UNCERTAIN").contains(status)) status="PENDING";
        return new CheckoutResponse(payment.getTripId(),payment.getPaymentId(),status,payment.getAmount(),payment.getCurrency(),
                "PENDING".equals(status) && a!=null && "PENDING".equals(a.getState()) ? a.getCheckoutUrl() : null,
                a==null?null:a.getExpiresAt(),"PAYOS",payment.getFailureReason()!=null?payment.getFailureReason():
                !config.configured()?CONFIG_MESSAGE:a==null?null:a.getMessage());
    }
    private String callback(String base,UUID trip) {
        return UriComponentsBuilder.fromUriString(base).replaceQueryParam("tripId",trip).build().toUriString();
    }
    private static BusinessException unavailable(String message) { return error(message,HttpStatus.SERVICE_UNAVAILABLE,"PAYOS_UNAVAILABLE"); }
    private static BusinessException error(String message,HttpStatus status,String code) { return new BusinessException(message,status,code); }
}

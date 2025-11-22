package edu.ucsal.fiadopay.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.ucsal.fiadopay.annotations.AntiFraud;
import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.controller.PaymentResponse;
import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.domain.WebhookDelivery;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import edu.ucsal.fiadopay.repo.PaymentRepository;
import edu.ucsal.fiadopay.repo.WebhookDeliveryRepository;

@Service
public class PaymentService {
  private final MerchantRepository merchants;
  private final PaymentRepository payments;
  private final WebhookDeliveryRepository deliveries;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final AntiFraudChecker antiFraudChecker;
  private final java.util.Map<String, edu.ucsal.fiadopay.payment.PaymentHandler> handlers;
  private final WebhookDeliveryCircuitCircuitBreaker circuitBreaker;
  private final edu.ucsal.fiadopay.service.DeliveryMetrics deliveryMetrics;

  @Value("${fiadopay.webhook-secret}") String secret;
  @Value("${fiadopay.processing-delay-ms}") long delay;
  @Value("${fiadopay.failure-rate}") double failRate;

  public PaymentService(MerchantRepository merchants,
                        PaymentRepository payments,
                        WebhookDeliveryRepository deliveries,
                        ObjectMapper objectMapper,
                        AntiFraudChecker antiFraudChecker,
                        ExecutorService fiadoExecutor,
                        ScheduledExecutorService fiadoScheduler,
                        java.util.Map<String, edu.ucsal.fiadopay.payment.PaymentHandler> handlers,
                        WebhookDeliveryCircuitCircuitBreaker circuitBreaker,
                        edu.ucsal.fiadopay.service.DeliveryMetrics deliveryMetrics) {
    this.merchants = merchants;
    this.payments = payments;
    this.deliveries = deliveries;
    this.objectMapper = objectMapper;
    this.antiFraudChecker = antiFraudChecker;
    this.executor = fiadoExecutor;
    this.scheduler = fiadoScheduler;
    this.handlers = handlers;
    this.circuitBreaker = circuitBreaker;
    this.deliveryMetrics = deliveryMetrics;
  }

  private Merchant merchantFromAuth(String auth){
    if (auth == null || !auth.startsWith("Bearer FAKE-")) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    var raw = auth.substring("Bearer FAKE-".length());
    long id;
    try {
      id = Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    var merchant = merchants.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (merchant.getStatus() != Merchant.Status.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return merchant;
  }

  @Transactional
  @AntiFraud(name = "HighAmount", threshold = 1000.0)
  public PaymentResponse createPayment(String auth, String idemKey, PaymentRequest req){
    var merchant = merchantFromAuth(auth);
    var mid = merchant.getId();

    // Anti-fraud quick check based on annotation-configured threshold
    antiFraudChecker.check(req);

    if (idemKey != null) {
      var existing = payments.findByIdempotencyKeyAndMerchantId(idemKey, mid);
      if(existing.isPresent()) return toResponse(existing.get());
    }

    BigDecimal total = req.amount();
    // create payment skeleton and let handler adjust fields if present
    var payment = Payment.builder()
        .id("pay_"+UUID.randomUUID().toString().substring(0,8))
        .merchantId(mid)
        .method(req.method()==null?"":req.method().toUpperCase())
        .amount(req.amount())
        .currency(req.currency())
        .installments(req.installments()==null?1:req.installments())
        .monthlyInterest(null)
        .totalWithInterest(req.amount())
        .status(Payment.Status.PENDING)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .idempotencyKey(idemKey)
        .metadataOrderId(req.metadataOrderId())
        .build();

    // delegate to a payment method handler if available
    var method = req.method()==null?"":req.method().toUpperCase();
    var handlerBean = handlers.values().stream().filter(h -> method.equalsIgnoreCase(h.type())).findFirst().orElse(null);
    if (handlerBean!=null) {
      handlerBean.apply(payment, req);
    } else {
      if ("CARD".equalsIgnoreCase(req.method()) && req.installments()!=null && req.installments()>1){
        var base = new BigDecimal("1.01");
        var factor = base.pow(req.installments());
        payment.setTotalWithInterest(req.amount().multiply(factor).setScale(2, RoundingMode.HALF_UP));
        payment.setMonthlyInterest(1.0);
      }
    }

    payments.save(payment);

    executor.submit(() -> processAndWebhook(payment.getId()));

    return toResponse(payment);
  }

  public PaymentResponse getPayment(String id){
    return toResponse(payments.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  public Map<String,Object> refund(String auth, String paymentId){
    var merchant = merchantFromAuth(auth);
    var p = payments.findById(paymentId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!merchant.getId().equals(p.getMerchantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    p.setStatus(Payment.Status.REFUNDED);
    p.setUpdatedAt(Instant.now());
    payments.save(p);
    sendWebhook(p);
    return Map.of("id","ref_"+UUID.randomUUID(),"status","PENDING");
  }

  private void processAndWebhook(String paymentId){
    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
    var p = payments.findById(paymentId).orElse(null);
    if (p==null) return;

    var approved = Math.random() > failRate;
    p.setStatus(approved ? Payment.Status.APPROVED : Payment.Status.DECLINED);
    p.setUpdatedAt(Instant.now());
    payments.save(p);

    sendWebhook(p);
  }

  private void sendWebhook(Payment p){
    var merchant = merchants.findById(p.getMerchantId()).orElse(null);
    if (merchant==null || merchant.getWebhookUrl()==null || merchant.getWebhookUrl().isBlank()) return;

    String payload;
    try {
      var data = Map.of(
          "paymentId", p.getId(),
          "status", p.getStatus().name(),
          "occurredAt", Instant.now().toString()
      );
      var event = Map.of(
          "id", "evt_"+UUID.randomUUID().toString().substring(0,8),
          "type", "payment.updated",
          "data", data
      );
      payload = objectMapper.writeValueAsString(event);
    } catch (Exception e) {
      // fallback mínimo: não envia webhook se falhar a serialização
      return;
    }

    var signature = hmac(payload, secret);

    var delivery = deliveries.save(WebhookDelivery.builder()
        .eventId("evt_"+UUID.randomUUID().toString().substring(0,8))
        .eventType("payment.updated")
        .paymentId(p.getId())
        .targetUrl(merchant.getWebhookUrl())
        .signature(signature)
        .payload(payload)
        .attempts(0)
        .delivered(false)
        .lastAttemptAt(null)
        .build());

    executor.submit(() -> tryDeliver(delivery.getId()));
  }

  private void tryDeliver(Long deliveryId){
    var d = deliveries.findById(deliveryId).orElse(null);
    if (d==null) return;
    var target = d.getTargetUrl();

    // circuit-breaker: if tripped, schedule next attempt after cooldown
    if (!circuitBreaker.allowRequest(target)){
      long cooldown = circuitBreaker.getCooldownMs(target);
      scheduler.schedule(() -> tryDeliver(deliveryId), cooldown>0?cooldown:1000L, TimeUnit.MILLISECONDS);
      return;
    }

    try {
      deliveryMetrics.incAttempt();
      var client = HttpClient.newHttpClient();
      var req = HttpRequest.newBuilder(URI.create(d.getTargetUrl()))
        .header("Content-Type","application/json")
        .header("X-Event-Type", d.getEventType())
        .header("X-Signature", d.getSignature())
        .POST(HttpRequest.BodyPublishers.ofString(d.getPayload()))
        .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      d.setAttempts(d.getAttempts()+1);
      d.setLastAttemptAt(Instant.now());
      d.setDelivered(res.statusCode()>=200 && res.statusCode()<300);
      deliveries.save(d);

      if (d.isDelivered()){
        circuitBreaker.recordSuccess(target);
        deliveryMetrics.incSuccess();
      } else {
        circuitBreaker.recordFailure(target);
        deliveryMetrics.incFailure();
      }

      if(!d.isDelivered() && d.getAttempts()<5){
        long backoff = (long) Math.pow(2, d.getAttempts()) * 1000L; // exponential backoff
        scheduler.schedule(() -> tryDeliver(deliveryId), backoff, TimeUnit.MILLISECONDS);
      }
    } catch (Exception e){
      d.setAttempts(d.getAttempts()+1);
      d.setLastAttemptAt(Instant.now());
      d.setDelivered(false);
      deliveries.save(d);
      circuitBreaker.recordFailure(target);
      deliveryMetrics.incFailure();
      if (d.getAttempts()<5){
        long backoff = (long) Math.pow(2, d.getAttempts()) * 1000L;
        scheduler.schedule(() -> tryDeliver(deliveryId), backoff, TimeUnit.MILLISECONDS);
      }
    }
  }

  private static String hmac(String payload, String secret){
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256"));
      return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes()));
    } catch (Exception e){ return ""; }
  }

  private PaymentResponse toResponse(Payment p){
    return new PaymentResponse(
        p.getId(), p.getStatus().name(), p.getMethod(),
        p.getAmount(), p.getInstallments(), p.getMonthlyInterest(),
        p.getTotalWithInterest()
    );
  }
}

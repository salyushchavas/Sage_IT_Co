package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.Payment;
import com.spire.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final PaymentRepository paymentRepository;

    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    /** The secret webhooks are signed with: the webhook secret, else the key secret, else none. */
    String signingSecret() {
        if (webhookSecret != null && !webhookSecret.isBlank()) return webhookSecret;
        if (keySecret != null && !keySecret.isBlank()) return keySecret;
        return null;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<ApiResponse<Void>> handleRazorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        // 1. Every event must be signed with our secret. Without a secret we
        //    can't tell a real event from a forged one, so nothing is processed.
        String secret = signingSecret();
        if (secret == null) {
            log.warn("Razorpay webhook received but no webhook secret is configured; ignoring it");
            return ResponseEntity.status(503).body(ApiResponse.error("Webhook not configured"));
        }
        if (signature == null || signature.isBlank() || !verifyWebhookSignature(rawBody, signature, secret)) {
            log.warn("Rejected Razorpay webhook with a missing or invalid signature");
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid signature"));
        }

        // 2. Parse event manually from raw body (avoid double-deserialization)
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> payload = mapper.readValue(rawBody, Map.class);

            String event = (String) payload.get("event");
            log.info("Razorpay webhook event: {}", event);

            if (event == null) {
                return ResponseEntity.ok(ApiResponse.success("No event", null));
            }

            switch (event) {
                case "payment.captured" -> handlePaymentCaptured(payload);
                case "payment.failed" -> handlePaymentFailed(payload);
                case "refund.created" -> handleRefund(payload);
                default -> log.info("Unhandled webhook event: {}", event);
            }

        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success("Webhook processed", null));
    }

    private void handlePaymentCaptured(Map<String, Object> payload) {
        try {
            Map<String, Object> paymentEntity = extractPaymentEntity(payload);
            String orderId = (String) paymentEntity.get("order_id");

            paymentRepository.findByRazorpayOrderId(orderId).ifPresent(payment -> {
                if (payment.getStatus() != Payment.Status.COMPLETED) {
                    payment.setStatus(Payment.Status.COMPLETED);
                    payment.setRazorpayPaymentId((String) paymentEntity.get("id"));
                    paymentRepository.save(payment);
                    log.info("Payment marked COMPLETED via webhook: {}", orderId);
                }
            });
        } catch (Exception e) {
            log.error("Error handling payment.captured: {}", e.getMessage());
        }
    }

    private void handlePaymentFailed(Map<String, Object> payload) {
        try {
            Map<String, Object> paymentEntity = extractPaymentEntity(payload);
            String orderId = (String) paymentEntity.get("order_id");

            paymentRepository.findByRazorpayOrderId(orderId).ifPresent(payment -> {
                payment.setStatus(Payment.Status.FAILED);
                paymentRepository.save(payment);
                log.warn("Payment FAILED via webhook: {}", orderId);
            });
        } catch (Exception e) {
            log.error("Error handling payment.failed: {}", e.getMessage());
        }
    }

    private void handleRefund(Map<String, Object> payload) {
        log.info("Refund webhook received: {}", payload.get("event"));
        // TODO: Handle refund — update payment status, notify user
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPaymentEntity(Map<String, Object> payload) {
        Map<String, Object> payloadData = (Map<String, Object>) payload.get("payload");
        Map<String, Object> payment = (Map<String, Object>) payloadData.get("payment");
        return (Map<String, Object>) payment.get("entity");
    }

    static boolean verifyWebhookSignature(String body, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String generated = HexFormat.of().formatHex(hash);
            // Constant-time comparison, so the signature can't be guessed byte by byte.
            return MessageDigest.isEqual(generated.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }
}

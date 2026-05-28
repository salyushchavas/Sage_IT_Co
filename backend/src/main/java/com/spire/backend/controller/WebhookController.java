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
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final PaymentRepository paymentRepository;

    @Value("${razorpay.webhook-secret:${razorpay.key-secret}}")
    private String webhookSecret;

    @PostMapping("/razorpay")
    public ResponseEntity<ApiResponse<Void>> handleRazorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        // 1. Verify webhook signature
        if (signature != null && !verifyWebhookSignature(rawBody, signature)) {
            log.warn("Invalid Razorpay webhook signature");
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

    private boolean verifyWebhookSignature(String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes());
            String generated = HexFormat.of().formatHex(hash);
            return generated.equals(signature);
        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }
}

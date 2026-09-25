package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checklist 5.4: Stripe Checkout over Stripe's plain HTTPS API (no extra
 * library). It's off until STRIPE_SECRET_KEY is set; webhooks are refused
 * until STRIPE_WEBHOOK_SECRET is set. STRIPE_API_BASE exists only so tests
 * can point it at a local stand-in.
 */
@Service
@Slf4j
public class StripeGateway {

    /** How old a signed webhook may be (Stripe's default tolerance). */
    static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.api-base:https://api.stripe.com}")
    private String apiBase;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public record CheckoutSession(String id, String url) {}

    public record SessionState(String id, String paymentStatus, String status, String paymentIntent,
                               long amountTotal, String currency) {
        public boolean paid() { return "paid".equals(paymentStatus); }
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public String webhookSecret() {
        return webhookSecret == null || webhookSecret.isBlank() ? null : webhookSecret;
    }

    /** A Checkout session for one payment in US dollars. */
    public CheckoutSession createCheckoutSession(long amountCents, String description, String customerEmail,
                                                 String clientReference, Map<String, String> metadata,
                                                 String successUrl, String cancelUrl) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("success_url", successUrl);
        form.put("cancel_url", cancelUrl);
        form.put("client_reference_id", clientReference);
        if (customerEmail != null && !customerEmail.isBlank()) form.put("customer_email", customerEmail);
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", "usd");
        form.put("line_items[0][price_data][unit_amount]", Long.toString(amountCents));
        form.put("line_items[0][price_data][product_data][name]", description);
        metadata.forEach((k, v) -> form.put("metadata[" + k + "]", v));
        JsonNode body = send(HttpRequest.newBuilder(URI.create(apiBase + "/v1/checkout/sessions"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))));
        return new CheckoutSession(body.path("id").asText(), body.path("url").asText());
    }

    /** The session as Stripe has it now (used when the participant comes back from Checkout). */
    public Optional<SessionState> retrieveSession(String sessionId) {
        if (!isConfigured() || sessionId == null || !sessionId.matches("[A-Za-z0-9_]+")) return Optional.empty();
        try {
            JsonNode body = send(HttpRequest.newBuilder(URI.create(apiBase + "/v1/checkout/sessions/" + sessionId)).GET());
            return Optional.of(state(body));
        } catch (RuntimeException e) {
            log.warn("Couldn't read Stripe session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** A checkout.session object from a webhook event. */
    public SessionState state(JsonNode session) {
        return new SessionState(session.path("id").asText(null), session.path("payment_status").asText(null),
                session.path("status").asText(null), session.path("payment_intent").asText(null),
                session.path("amount_total").asLong(-1), session.path("currency").asText(null));
    }

    /**
     * Stripe's webhook signature: header "t=<unix time>,v1=<hex HMAC-SHA256
     * of "t.payload">", compared in constant time, and not older than five
     * minutes (so a captured event can't be replayed later).
     */
    public static boolean verifySignature(String payload, String header, String secret, long nowEpochSeconds) {
        if (payload == null || header == null || secret == null || secret.isBlank()) return false;
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) timestamp = kv[1];
            else if ("v1".equals(kv[0])) signatures.add(kv[1]);
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        long t;
        try {
            t = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - t) > SIGNATURE_TOLERANCE_SECONDS) return false;
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;
        }
        for (String sig : signatures) {
            if (MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    public JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a JSON event");
        }
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> res = http.send(request
                    .header("Authorization", "Bearer " + secretKey)
                    .timeout(Duration.ofSeconds(20))
                    .build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                String message = body.path("error").path("message").asText("Stripe error " + res.statusCode());
                throw new IllegalStateException("The payment page couldn't be opened: " + message);
            }
            return body;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The payment service didn't answer. Please try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while contacting the payment service.", e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new IllegalStateException("Online payment isn't set up yet.");
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}

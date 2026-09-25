package com.spire.backend.controller;

import com.spire.backend.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Checklist 0.5: the payment webhook must be signed with our secret.
 * No secret configured → 503 and nothing processed; a missing or wrong
 * signature → 400; only a correct signature is processed.
 */
class WebhookSignatureTest {

    private static final String BODY = "{\"event\":\"payment.failed\",\"payload\":{}}";

    private static WebhookController controller(String webhookSecret, String keySecret) {
        WebhookController c = new WebhookController(mock(PaymentRepository.class),
                mock(com.spire.backend.service.StripeGateway.class), mock(com.spire.backend.service.CourseCheckoutService.class));
        ReflectionTestUtils.setField(c, "webhookSecret", webhookSecret);
        ReflectionTestUtils.setField(c, "keySecret", keySecret);
        return c;
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void withoutASecretNothingIsProcessed() throws Exception {
        assertEquals(503, controller("", "").handleRazorpayWebhook(BODY, sign(BODY, "anything")).getStatusCode().value());
    }

    @Test
    void missingOrWrongSignaturesAreRefused() throws Exception {
        WebhookController c = controller("whsec-test", "");
        assertEquals(400, c.handleRazorpayWebhook(BODY, null).getStatusCode().value());
        assertEquals(400, c.handleRazorpayWebhook(BODY, "").getStatusCode().value());
        assertEquals(400, c.handleRazorpayWebhook(BODY, sign(BODY, "some-other-secret")).getStatusCode().value());
        assertEquals(400, c.handleRazorpayWebhook(BODY + " ", sign(BODY, "whsec-test")).getStatusCode().value());
    }

    @Test
    void aCorrectSignatureIsProcessed() throws Exception {
        assertEquals(200, controller("whsec-test", "").handleRazorpayWebhook(BODY, sign(BODY, "whsec-test")).getStatusCode().value());
        // Falls back to the key secret when no webhook secret is set.
        assertEquals(200, controller("", "key-test").handleRazorpayWebhook(BODY, sign(BODY, "key-test")).getStatusCode().value());
    }
}

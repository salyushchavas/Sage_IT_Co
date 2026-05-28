package com.spire.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Email relay client. Posts to a Vercel-hosted Next.js API route
 * (see {@code frontend/src/app/api/send-email/route.ts}) which
 * forwards to Gmail SMTP via Nodemailer.
 *
 * Why the indirection: Railway's egress firewall blocks SMTP on
 * every port (587, 465, 25) so the JavaMail-based path could never
 * connect. Vercel's Node runtime can reach SMTP, so the backend
 * formats the message and delegates the wire-level send.
 *
 * Authentication is a shared secret in the JSON body; the Vercel
 * route refuses any request whose {@code secret} doesn't match.
 *
 * Sends are best-effort and synchronous — slow Vercel responses
 * delay the calling request, but every callsite already wraps
 * sendEmail in try/catch so an outage can't break signup,
 * enrollment, etc.
 */
@Service
@Slf4j
public class EmailService {

    @Value("${email.relay.url:}")
    private String relayUrl;

    @Value("${email.relay.secret:}")
    private String relaySecret;

    private final RestTemplate restTemplate;

    public EmailService() {
        // Tight timeouts so a hung Vercel deploy can't pin the calling
        // thread for the default minutes-long fetch. 5s connect / 15s
        // read is ample for a JSON POST + Gmail handoff.
        // Configured via SimpleClientHttpRequestFactory for portability
        // across Spring Boot versions where RestTemplateBuilder's
        // timeout API differs.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * True when both the relay URL and shared secret are wired up.
     * Used by the test endpoint and the inactive-nudge cron to bail
     * out cleanly when email isn't configured for the environment.
     */
    public boolean isConfigured() {
        return relayUrl != null && !relayUrl.isBlank()
                && relaySecret != null && !relaySecret.isBlank();
    }

    /**
     * Posts the message to the relay. Logs and swallows any
     * exception — callers expect this to never throw.
     */
    public void sendEmail(String to, String subject, String htmlBody) {
        sendEmail(to, subject, htmlBody, List.of());
    }

    /**
     * Send a message with one or more PDF / binary attachments.
     * Attachments are base64-encoded into the JSON payload and
     * decoded by the Vercel relay before handing them to Nodemailer.
     * Keep payload size reasonable — relay HTTP body is bounded by
     * Vercel's request limits (4.5 MB on hobby/Pro routes).
     */
    public void sendEmail(String to, String subject, String htmlBody, List<Attachment> attachments) {
        if (!isConfigured()) {
            log.warn("Email relay not configured — skipping send: subject='{}' to='{}'", subject, to);
            return;
        }
        try {
            // Use Object map (not String map) so we can mix the JSON
            // string fields with the attachments array.
            Map<String, Object> payload = new HashMap<>();
            payload.put("to", to);
            payload.put("subject", subject);
            payload.put("html", htmlBody);
            payload.put("secret", relaySecret);

            if (attachments != null && !attachments.isEmpty()) {
                List<Map<String, String>> attachList = new ArrayList<>(attachments.size());
                for (Attachment a : attachments) {
                    if (a == null || a.content() == null || a.filename() == null) continue;
                    Map<String, String> entry = new HashMap<>();
                    entry.put("filename", a.filename());
                    entry.put("contentType", a.contentType() == null
                            ? "application/octet-stream" : a.contentType());
                    entry.put("contentBase64", Base64.getEncoder().encodeToString(a.content()));
                    attachList.add(entry);
                }
                payload.put("attachments", attachList);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            // Use LinkedHashMap so any future logging of the response
            // body keeps a stable field order.
            @SuppressWarnings("rawtypes")
            ResponseEntity<LinkedHashMap> response = restTemplate.postForEntity(
                    relayUrl, request, LinkedHashMap.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            boolean ok = response.getStatusCode().is2xxSuccessful()
                    && body != null
                    && Boolean.TRUE.equals(body.get("ok"));

            if (ok) {
                log.info("Email relayed via Vercel: subject='{}' to='{}' attachments={}",
                        subject, to, attachments == null ? 0 : attachments.size());
            } else {
                String detail = body != null && body.get("message") != null
                        ? body.get("message").toString() : "no body";
                log.error("Relay rejected send: subject='{}' to='{}': {}", subject, to, detail);
            }
        } catch (Exception e) {
            log.error("Email relay call failed: subject='{}' to='{}': {}",
                    subject, to, e.getMessage());
        }
    }

    /**
     * Single attachment payload — raw bytes, filename, and MIME type.
     * The relay client base64-encodes {@code content} into the JSON
     * body the Vercel route expects.
     */
    public record Attachment(String filename, String contentType, byte[] content) {}
}

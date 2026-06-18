package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Direct Gmail SMTP sender via Spring's JavaMailSender. Replaces
 * the previous "POST to a Vercel relay" pattern -- Sage owns its
 * own SMTP credentials on Railway and ships them straight to
 * Gmail.
 *
 * IMPORTANT: if the Railway egress firewall blocks outbound SMTP
 * (port 587), every send here will silently time out. The original
 * Spire codebase used the relay pattern specifically because that
 * was the case. Verify SMTP is reachable from Railway before
 * relying on this in prod -- if not, the rollback is to re-set
 * EMAIL_RELAY_URL / EMAIL_RELAY_SECRET env vars and restore the
 * old relay-based implementation.
 *
 * Public API kept identical to the relay version so callers
 * (EmailTemplateService and the cron jobs) need no changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final BrandConfig brandConfig;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    /**
     * True when SMTP credentials are configured. Used by the test
     * endpoint and any cron path that wants to bail early when
     * email isn't wired up for the environment.
     */
    public boolean isConfigured() {
        return fromEmail != null && !fromEmail.isBlank();
    }

    public void sendEmail(String to, String subject, String htmlBody) {
        sendEmail(to, subject, htmlBody, List.of());
    }

    /**
     * Send an HTML message with optional binary attachments.
     * Attachments are added via MimeMessageHelper -- bytes are
     * wrapped in ByteArrayResource so JavaMail picks them up
     * without writing temp files.
     *
     * Errors are logged and swallowed; callers historically expect
     * this method never to throw.
     */
    public void sendEmail(String to, String subject, String htmlBody, List<Attachment> attachments) {
        sendFrom(fromEmail, to, subject, htmlBody, attachments);
    }

    /**
     * Send with an EXPLICIT From address (e.g. the brand no-reply mailbox),
     * overriding the default SMTP-account sender. Note: the SMTP provider
     * (Gmail / Workspace) must permit "send-as" the given address, or it may
     * rewrite the header to the authenticated account. Falls back to the
     * configured sender when {@code fromAddress} is blank.
     */
    public void sendEmailFrom(String fromAddress, String to, String subject, String htmlBody) {
        String from = (fromAddress == null || fromAddress.isBlank()) ? fromEmail : fromAddress;
        sendFrom(from, to, subject, htmlBody, List.of());
    }

    private void sendFrom(String fromAddress, String to, String subject,
                          String htmlBody, List<Attachment> attachments) {
        if (!isConfigured()) {
            log.warn("SMTP not configured -- skipping send: subject='{}' to='{}'", subject, to);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true so we can attach files; UTF-8 keeps subject
            // / body encoded properly for non-ASCII content.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, brandConfig.getName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            if (attachments != null) {
                for (Attachment a : attachments) {
                    if (a == null || a.content() == null || a.filename() == null) continue;
                    String contentType = a.contentType() == null
                            ? "application/octet-stream"
                            : a.contentType();
                    helper.addAttachment(
                            a.filename(),
                            new ByteArrayResource(a.content()),
                            contentType);
                }
            }

            mailSender.send(message);

            log.info("Email sent via SMTP: subject='{}' to='{}' attachments={}",
                    subject, to, attachments == null ? 0 : attachments.size());
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to build email message for {}: {}", to, e.getMessage());
        } catch (Exception e) {
            // JavaMailSender wraps SMTP failures in MailSendException
            // (RuntimeException subtype) -- catch broadly so a Railway
            // egress block / Gmail throttle doesn't crash the calling
            // request.
            log.error("SMTP send failed: subject='{}' to='{}': {}",
                    subject, to, e.getMessage());
        }
    }

    /**
     * Single attachment payload -- raw bytes, filename, and MIME type.
     */
    public record Attachment(String filename, String contentType, byte[] content) {}
}

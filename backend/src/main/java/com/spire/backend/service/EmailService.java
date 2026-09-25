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
 * (EmailTemplateService and the cron jobs) need no changes, except
 * that each send now says whether it went out (checklist 1.4).
 *
 * Every attempt is recorded in the email log (EmailLogService) as
 * SENT, FAILED (with the mail server's reason) or SKIPPED (email not
 * set up here), with the template that sent it as the email type.
 * The body is never stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final BrandConfig brandConfig;
    private final EmailLogService emailLogService;

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

    /** True when the mail server accepted the email. */
    public boolean sendEmail(String to, String subject, String htmlBody) {
        return sendEmail(to, subject, htmlBody, List.of());
    }

    /**
     * Send an HTML message with optional binary attachments.
     * Attachments are added via MimeMessageHelper -- bytes are
     * wrapped in ByteArrayResource so JavaMail picks them up
     * without writing temp files.
     *
     * Errors are logged and swallowed; callers historically expect
     * this method never to throw. Returns true only when the mail
     * server accepted the email; every attempt is in the email log.
     */
    public boolean sendEmail(String to, String subject, String htmlBody, List<Attachment> attachments) {
        return sendFrom(fromEmail, to, subject, htmlBody, attachments);
    }

    /**
     * Send with an EXPLICIT From address (e.g. the brand no-reply mailbox),
     * overriding the default SMTP-account sender. Note: the SMTP provider
     * (Gmail / Workspace) must permit "send-as" the given address, or it may
     * rewrite the header to the authenticated account. Falls back to the
     * configured sender when {@code fromAddress} is blank.
     */
    public boolean sendEmailFrom(String fromAddress, String to, String subject, String htmlBody) {
        String from = (fromAddress == null || fromAddress.isBlank()) ? fromEmail : fromAddress;
        return sendFrom(from, to, subject, htmlBody, List.of());
    }

    /**
     * Subjects are logged, and a verification email's subject carries the
     * one-time code, which must never land in logs. Mask any 6-digit run.
     */
    static String safeSubject(String subject) {
        return subject == null ? null : subject.replaceAll("\\b\\d{6}\\b", "******");
    }

    private boolean sendFrom(String fromAddress, String addressedTo, String subject,
                             String htmlBody, List<Attachment> attachments) {
        String[] origin = origin();
        // Staff onboarding: an account with a personal email gets its email
        // there (its login email may be a company address with no mailbox).
        String to = addressedTo;
        try {
            to = emailLogService.deliveryAddress(addressedTo);
        } catch (Exception e) {
            log.warn("Couldn't look up the delivery address; sending to the address given: {}", e.getMessage());
        }
        if (!isConfigured()) {
            log.warn("SMTP not configured -- skipping send: subject='{}' to='{}'", safeSubject(subject), to);
            logAttempt(to, subject, origin, EmailLogService.SKIPPED, "Email is not set up on this server");
            return false;
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
                    safeSubject(subject), to, attachments == null ? 0 : attachments.size());
            logAttempt(to, subject, origin, EmailLogService.SENT, null);
            return true;
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to build email message for {}: {}", to, e.getMessage());
            logAttempt(to, subject, origin, EmailLogService.FAILED, "Couldn't build the email: " + e.getMessage());
            return false;
        } catch (Exception e) {
            // JavaMailSender wraps SMTP failures in MailSendException
            // (RuntimeException subtype) -- catch broadly so a Railway
            // egress block / Gmail throttle doesn't crash the calling
            // request.
            log.error("SMTP send failed: subject='{}' to='{}': {}",
                    safeSubject(subject), to, e.getMessage());
            logAttempt(to, subject, origin, EmailLogService.FAILED, reason(e));
            return false;
        }
    }

    /**
     * The mail server's own answer (e.g. "550 5.1.1 Mailbox unavailable")
     * rather than the whole exception chain, for the email log.
     */
    static String reason(Throwable e) {
        Throwable t = e;
        if (e instanceof org.springframework.mail.MailSendException mse && !mse.getFailedMessages().isEmpty()) {
            t = mse.getFailedMessages().values().iterator().next();
        }
        Throwable deepest = t;
        for (int i = 0; i < 10; i++) {
            Throwable next = deepest.getCause();
            if (next == null && deepest instanceof MessagingException me) next = me.getNextException();
            if (next == null || next == deepest) break;
            deepest = next;
        }
        String msg = deepest.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg.trim();
    }

    /** Records the attempt; the email log must never break sending. */
    private void logAttempt(String to, String subject, String[] origin, String status, String error) {
        try {
            emailLogService.record(to, subject, origin[0], origin[1], status, error);
        } catch (Exception e) {
            log.warn("Couldn't record email log for '{}': {}", safeSubject(subject), e.getMessage());
        }
    }

    /**
     * {email type, trigger} for the email being sent, taken from the call
     * stack: the type is the template method that built it
     * ("sendDocumentReminderEmail" becomes DOCUMENT_REMINDER) and the
     * trigger is the code that asked for it (Class.method).
     */
    static String[] origin() {
        java.util.List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(st -> st
                .filter(f -> f.getClassName().startsWith("com.spire.backend.")
                        && !f.getClassName().equals(EmailService.class.getName())
                        && !f.getClassName().contains("$$"))
                .limit(8)
                .toList());
        if (frames.isEmpty()) return new String[]{"OTHER", null};
        StackWalker.StackFrame template = frames.get(0);
        String trigger = null;
        for (StackWalker.StackFrame f : frames) {
            if (!f.getClassName().equals(template.getClassName())) {
                trigger = simpleName(f.getClassName()) + "." + f.getMethodName();
                break;
            }
        }
        return new String[]{typeFromMethod(template.getMethodName()), trigger};
    }

    /** sendDocumentReminderEmail → DOCUMENT_REMINDER; sendConsultantOtp → CONSULTANT_OTP. */
    static String typeFromMethod(String method) {
        String m = method == null ? "" : method;
        if (m.startsWith("send") && m.length() > 4) m = m.substring(4);
        if (m.endsWith("Email") && m.length() > 5) m = m.substring(0, m.length() - 5);
        String snake = m.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
        return snake.isBlank() ? "OTHER" : snake;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /**
     * Single attachment payload -- raw bytes, filename, and MIME type.
     */
    public record Attachment(String filename, String contentType, byte[] content) {}
}

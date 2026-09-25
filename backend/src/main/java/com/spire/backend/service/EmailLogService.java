package com.spire.backend.service;

import com.spire.backend.entity.EmailLog;
import com.spire.backend.entity.User;
import com.spire.backend.repository.EmailLogRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checklist 1.4: the record of every email the platform tried to send,
 * sent or not, so a failed email no longer looks sent and Operations can
 * see what went out (roadmap §9 communication log).
 *
 * Written in its own transaction, so the row stays even if the caller's
 * work is rolled back afterwards (the email went out regardless).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailLogService {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private final EmailLogRepository emailLogRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String recipient, String subject, String emailType, String trigger,
                       String status, String error) {
        EmailLog row = EmailLog.builder()
                .userId(userIdFor(recipient))
                .emailType(cut(emailType == null ? "OTHER" : emailType, 50))
                .recipient(cut(recipient, 255))
                .subject(cut(EmailService.safeSubject(subject), 500))
                .status(status)
                .triggerEvent(cut(trigger, 100))
                .errorMessage(cut(error, 500))
                .build();
        emailLogRepository.save(row);
    }

    /** One row of the Operations email log. */
    public record Row(Long id, Long userId, String emailType, String recipient, String subject,
                      String status, String triggerEvent, String errorMessage, LocalDateTime sentAt) {}

    /**
     * The Operations email log: newest first, at most 300 rows. Filter by
     * status (SENT / FAILED / SKIPPED), and/or by a participant (user id)
     * or an email address.
     */
    @Transactional(readOnly = true)
    public List<Row> list(String status, Long userId, String email) {
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        List<EmailLog> rows;
        if (userId != null) rows = emailLogRepository.findTop300ByUserIdOrderBySentAtDesc(userId);
        else if (email != null && !email.isBlank())
            rows = emailLogRepository.findTop300ByRecipientIgnoreCaseOrderBySentAtDesc(email.trim());
        else if (st != null) rows = emailLogRepository.findTop300ByStatusOrderBySentAtDesc(st);
        else rows = emailLogRepository.findTop300ByOrderBySentAtDesc();
        // An email sent while its account was being created (the sign-up
        // code) is recorded before that account exists; link it now.
        Map<String, Long> resolved = new HashMap<>();
        return rows.stream()
                .filter(r -> st == null || st.equals(r.getStatus()))
                .map(r -> new Row(r.getId(),
                        r.getUserId() != null ? r.getUserId()
                                : resolved.computeIfAbsent(String.valueOf(r.getRecipient()), k -> userIdFor(r.getRecipient())),
                        r.getEmailType(), r.getRecipient(), r.getSubject(), r.getStatus(),
                        r.getTriggerEvent(), r.getErrorMessage(), r.getSentAt()))
                .toList();
    }

    /**
     * Staff onboarding: where an email to this address should really go.
     * An account with a personal email gets everything there (its login
     * email may be a company address that isn't a mailbox); anyone else
     * gets it at the address given.
     */
    public String deliveryAddress(String to) {
        if (to == null || to.isBlank()) return to;
        return userRepository.findByEmail(to.trim())
                .map(User::getPersonalEmail)
                .filter(p -> p != null && !p.isBlank())
                .orElse(to);
    }

    private Long userIdFor(String recipient) {
        if (recipient == null || recipient.isBlank()) return null;
        try {
            return userRepository.findByEmail(recipient.trim())
                    .or(() -> {
                        List<User> matches = userRepository.findAllByEmailIgnoreCase(recipient.trim());
                        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
                    })
                    // Staff onboarding: emails delivered to a personal address.
                    .or(() -> userRepository.findFirstByPersonalEmailIgnoreCase(recipient.trim()))
                    .map(User::getId)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

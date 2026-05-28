package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.SalesInquiryDTO;
import com.spire.backend.dto.SalesMessageDTO;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.QuoteResponse;
import com.spire.backend.entity.SalesInquiry;
import com.spire.backend.entity.SalesMessage;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.QuoteResponseRepository;
import com.spire.backend.repository.SalesInquiryRepository;
import com.spire.backend.repository.SalesMessageRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SalesService {

    private static final int PREVIEW_LEN = 140;

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_QUOTED = "QUOTED";
    private static final String STATUS_CONVERTED = "CONVERTED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_LOST = "LOST";

    private final SalesInquiryRepository inquiryRepository;
    private final SalesMessageRepository messageRepository;
    private final QuoteResponseRepository quoteResponseRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final EnrollmentService enrollmentService;
    private final ObjectMapper objectMapper;
    private final EmailTemplateService emailTemplateService;

    // ─── Student: create inquiry ─────────────────────────────────

    @Transactional
    public SalesInquiryDTO createInquiry(Long userId, Long courseId, String subject,
                                          String budgetRange, String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        // One open inquiry per (student, course) — show a clear error
        // instead of duplicating threads.
        if (inquiryRepository.findActiveForUserAndCourse(userId, courseId).isPresent()) {
            throw new IllegalArgumentException(
                    "You already have an active inquiry for this course. Check your Messages.");
        }

        String resolvedSubject = (subject != null && !subject.isBlank())
                ? subject.trim() : course.getTitle();

        SalesInquiry inquiry = inquiryRepository.save(SalesInquiry.builder()
                .user(user)
                .course(course)
                .status(STATUS_NEW)
                .subject(resolvedSubject)
                .budgetRange(budgetRange != null ? budgetRange.trim() : null)
                .build());

        SalesMessage msg = messageRepository.save(SalesMessage.builder()
                .inquiry(inquiry)
                .sender(user)
                .message(firstMessage.trim())
                .isQuote(false)
                .build());

        return SalesInquiryDTO.detail(inquiry,
                List.of(SalesMessageDTO.from(msg, null)));
    }

    // ─── Conversation messages ────────────────────────────────────

    @Transactional
    public SalesInquiryDTO postMessage(Long inquiryId, Long senderId, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body is required");
        }
        SalesInquiry inquiry = loadAuthorized(inquiryId, senderId);
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", senderId));

        messageRepository.save(SalesMessage.builder()
                .inquiry(inquiry)
                .sender(sender)
                .message(body.trim())
                .isQuote(false)
                .build());

        // Move NEW → IN_PROGRESS once the conversation actually starts.
        if (STATUS_NEW.equals(inquiry.getStatus())) {
            inquiry.setStatus(STATUS_IN_PROGRESS);
            inquiryRepository.save(inquiry);
        }

        // Notify the *student* when an instructor or admin replies. We
        // skip the email when the sender is the student themselves
        // (they don't need an inbox ping for their own outgoing
        // message). The "instructor" name is the actual sender — admin
        // replies show as the admin's name, which matches the in-app
        // attribution.
        try {
            User student = inquiry.getUser();
            if (student != null && !student.getId().equals(senderId)) {
                String courseTitle = inquiry.getCourse() != null
                        ? inquiry.getCourse().getTitle() : "your inquiry";
                emailTemplateService.sendSalesReplyEmail(
                        student, sender.getFullName(), courseTitle,
                        body.trim(), inquiry.getId());
            }
        } catch (Exception ignored) {}

        return getInquiryDetail(inquiryId, senderId);
    }

    // ─── Instructor: send quote ──────────────────────────────────

    @Transactional
    public SalesInquiryDTO sendQuote(Long inquiryId, Long instructorId, String message,
                                      BigDecimal quotedPrice, List<Map<String, Object>> items) {
        SalesInquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesInquiry", "id", inquiryId));

        // Only the course's instructor (or admin) can send a quote.
        Long courseInstructorId = inquiry.getCourse() != null && inquiry.getCourse().getInstructor() != null
                ? inquiry.getCourse().getInstructor().getId() : null;
        if (!instructorId.equals(courseInstructorId) && !isAdmin(instructorId)) {
            throw new UnauthorizedException("Only the course instructor can send quotes for this inquiry");
        }

        if (quotedPrice == null || quotedPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quoted price must be a non-negative number");
        }

        User sender = userRepository.findById(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", instructorId));

        String itemsJson = serializeItems(items);

        messageRepository.save(SalesMessage.builder()
                .inquiry(inquiry)
                .sender(sender)
                .message(message != null && !message.isBlank() ? message.trim() : "Here's a custom quote for you.")
                .isQuote(true)
                .quotedPrice(quotedPrice)
                .quotedItems(itemsJson)
                .build());

        inquiry.setStatus(STATUS_QUOTED);
        inquiryRepository.save(inquiry);

        return getInquiryDetail(inquiryId, instructorId);
    }

    // ─── Student: accept quote ───────────────────────────────────

    @Transactional
    public SalesInquiryDTO acceptQuote(Long inquiryId, Long studentId, Long messageId) {
        SalesInquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesInquiry", "id", inquiryId));

        if (inquiry.getUser() == null || !inquiry.getUser().getId().equals(studentId)) {
            throw new UnauthorizedException("Only the student on this inquiry can accept quotes");
        }

        SalesMessage quoteMsg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesMessage", "id", messageId));
        if (!Boolean.TRUE.equals(quoteMsg.getIsQuote())
                || !quoteMsg.getInquiry().getId().equals(inquiryId)) {
            throw new IllegalArgumentException("That message is not a quote for this inquiry");
        }

        // Idempotent — don't double-respond if the student clicks twice.
        QuoteResponse existing = quoteResponseRepository.findByMessageId(messageId).orElse(null);
        if (existing != null && "ACCEPTED".equals(existing.getStatus())) {
            return getInquiryDetail(inquiryId, studentId);
        }

        quoteResponseRepository.save(QuoteResponse.builder()
                .message(quoteMsg)
                .status("ACCEPTED")
                .build());

        // Best-effort enrollment at the negotiated price. We swallow
        // "already enrolled" because the inquiry may have been opened
        // for an enrollment that already exists; the price tag is
        // preserved on the message either way.
        try {
            enrollmentService.enrollUser(studentId, inquiry.getCourse().getId());
        } catch (IllegalArgumentException ignored) {
            // already enrolled — that's fine
        }

        inquiry.setStatus(STATUS_CONVERTED);
        inquiry.setClosedAt(LocalDateTime.now());
        inquiryRepository.save(inquiry);

        return getInquiryDetail(inquiryId, studentId);
    }

    @Transactional
    public SalesInquiryDTO declineQuote(Long inquiryId, Long studentId, Long messageId) {
        SalesInquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesInquiry", "id", inquiryId));
        if (inquiry.getUser() == null || !inquiry.getUser().getId().equals(studentId)) {
            throw new UnauthorizedException("Only the student on this inquiry can decline quotes");
        }
        SalesMessage quoteMsg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesMessage", "id", messageId));
        if (!Boolean.TRUE.equals(quoteMsg.getIsQuote())
                || !quoteMsg.getInquiry().getId().equals(inquiryId)) {
            throw new IllegalArgumentException("That message is not a quote for this inquiry");
        }
        if (quoteResponseRepository.findByMessageId(messageId).isEmpty()) {
            quoteResponseRepository.save(QuoteResponse.builder()
                    .message(quoteMsg)
                    .status("DECLINED")
                    .build());
        }
        // Decline alone doesn't close the thread — the student may
        // still be negotiating. Leave status at QUOTED so the
        // instructor knows to follow up.
        return getInquiryDetail(inquiryId, studentId);
    }

    // ─── Close ───────────────────────────────────────────────────

    @Transactional
    public SalesInquiryDTO closeInquiry(Long inquiryId, Long actorId, String reason) {
        SalesInquiry inquiry = loadAuthorized(inquiryId, actorId);
        // CONVERTED is terminal — don't reopen.
        if (STATUS_CONVERTED.equals(inquiry.getStatus())) {
            return getInquiryDetail(inquiryId, actorId);
        }
        // "LOST" if the closer is the instructor (the lead didn't
        // convert); "CLOSED" if the student closed it themselves.
        boolean isStudent = inquiry.getUser() != null && inquiry.getUser().getId().equals(actorId);
        inquiry.setStatus(isStudent ? STATUS_CLOSED : STATUS_LOST);
        inquiry.setClosedAt(LocalDateTime.now());
        inquiryRepository.save(inquiry);

        if (reason != null && !reason.isBlank()) {
            User actor = userRepository.findById(actorId).orElse(null);
            if (actor != null) {
                messageRepository.save(SalesMessage.builder()
                        .inquiry(inquiry)
                        .sender(actor)
                        .message("Inquiry closed: " + reason.trim())
                        .isQuote(false)
                        .build());
            }
        }
        return getInquiryDetail(inquiryId, actorId);
    }

    // ─── Reads ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SalesInquiryDTO> getInquiriesForStudent(Long userId) {
        return inquiryRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SalesInquiryDTO> getInquiriesForInstructor(Long instructorId) {
        return inquiryRepository.findForInstructor(instructorId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SalesInquiryDTO> getAllInquiries() {
        return inquiryRepository.findAllOrdered().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public SalesInquiryDTO getInquiryDetail(Long inquiryId, Long requesterId) {
        SalesInquiry inquiry = loadAuthorized(inquiryId, requesterId);
        List<SalesMessage> messages = messageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId);
        List<SalesMessageDTO> dtos = messages.stream()
                .map(m -> {
                    QuoteResponse resp = Boolean.TRUE.equals(m.getIsQuote())
                            ? quoteResponseRepository.findByMessageId(m.getId()).orElse(null)
                            : null;
                    return SalesMessageDTO.from(m, resp);
                })
                .toList();
        return SalesInquiryDTO.detail(inquiry, dtos);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        long total = inquiryRepository.count();
        long newCount = inquiryRepository.countByStatus(STATUS_NEW);
        long inProgress = inquiryRepository.countByStatus(STATUS_IN_PROGRESS);
        long quoted = inquiryRepository.countByStatus(STATUS_QUOTED);
        long converted = inquiryRepository.countByStatus(STATUS_CONVERTED);
        long closed = inquiryRepository.countByStatus(STATUS_CLOSED);
        long lost = inquiryRepository.countByStatus(STATUS_LOST);

        double conversionRate = total > 0 ? (converted * 100.0 / total) : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalInquiries", total);
        body.put("newCount", newCount);
        body.put("inProgressCount", inProgress);
        body.put("quotedCount", quoted);
        body.put("convertedCount", converted);
        body.put("closedCount", closed);
        body.put("lostCount", lost);
        body.put("conversionRate", Math.round(conversionRate * 10.0) / 10.0);
        return body;
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private SalesInquiry loadAuthorized(Long inquiryId, Long actorId) {
        SalesInquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesInquiry", "id", inquiryId));

        Long studentId = inquiry.getUser() != null ? inquiry.getUser().getId() : null;
        Long instructorId = inquiry.getCourse() != null && inquiry.getCourse().getInstructor() != null
                ? inquiry.getCourse().getInstructor().getId() : null;

        if (!actorId.equals(studentId) && !actorId.equals(instructorId) && !isAdmin(actorId)) {
            throw new UnauthorizedException("You don't have access to this inquiry");
        }
        return inquiry;
    }

    private boolean isAdmin(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRole() != null && "ADMIN".equals(u.getRole().getName()))
                .orElse(false);
    }

    private SalesInquiryDTO toSummary(SalesInquiry inquiry) {
        List<SalesMessage> messages = messageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiry.getId());
        SalesMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        String preview = last != null ? truncate(last.getMessage()) : null;
        String previewSender = last != null && last.getSender() != null ? last.getSender().getFullName() : null;
        LocalDateTime previewAt = last != null ? last.getCreatedAt() : inquiry.getUpdatedAt();
        return SalesInquiryDTO.summary(inquiry, preview, previewSender, previewAt);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > PREVIEW_LEN ? s.substring(0, PREVIEW_LEN) + "…" : s;
    }

    private String serializeItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            // Normalize numeric prices into a single shape the frontend expects.
            List<Map<String, Object>> normalized = items.stream().map(raw -> {
                Map<String, Object> m = new HashMap<>();
                m.put("item", raw.get("item") != null ? raw.get("item").toString() : "");
                Object p = raw.get("price");
                BigDecimal price;
                if (p instanceof Number) {
                    price = new BigDecimal(p.toString());
                } else if (p != null) {
                    try { price = new BigDecimal(p.toString()); } catch (Exception e) { price = BigDecimal.ZERO; }
                } else {
                    price = BigDecimal.ZERO;
                }
                m.put("price", price);
                return m;
            }).toList();
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}

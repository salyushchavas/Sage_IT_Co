package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.UserRecord;
import com.spire.backend.repository.UserRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Append-only audit logger. Every meaningful user action funnels
 * through {@link #record(Long, String, String, String, String, Map)}
 * (or the 6-arg overload that takes an explicit HttpServletRequest).
 *
 * Record categories: ACCOUNT, LEARNING, ASSESSMENT, MENTORSHIP,
 * PAYMENT, CERTIFICATE, SECURITY.
 *
 * Failures here NEVER block the calling action — record() catches
 * everything internally and logs to the application logger so the
 * primary flow (enrollment, payment, etc.) still completes if the
 * audit save fails.
 *
 * Records are written in REQUIRES_NEW so a rollback of the calling
 * transaction doesn't erase the audit row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    private final UserRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public static final class Category {
        public static final String ACCOUNT = "ACCOUNT";
        public static final String LEARNING = "LEARNING";
        public static final String ASSESSMENT = "ASSESSMENT";
        public static final String MENTORSHIP = "MENTORSHIP";
        public static final String PAYMENT = "PAYMENT";
        public static final String CERTIFICATE = "CERTIFICATE";
        public static final String SECURITY = "SECURITY";
        // Phase 1A additions — the participant lifecycle surfaces.
        public static final String WORKFLOW = "WORKFLOW";
        public static final String DOCUMENT = "DOCUMENT";
        public static final String EMAIL = "EMAIL";
        private Category() {}
    }

    /**
     * Lightweight wrapper around {@link #record(Long, String, String,
     * String, String, Map)} for call sites that only have a category
     * + short title + description. Useful for the participant
     * lifecycle ({@code WorkflowService}, document review, …) where
     * the structured details map is empty or already serialised
     * upstream.
     */
    public void logAction(Long userId, String category, String title, String description) {
        record(userId, category, category, title, description, null);
    }

    /** Same as {@link #logAction(Long, String, String, String)} with structured details. */
    public void logAction(Long userId, String category, String title,
                          String description, Map<String, Object> details) {
        record(userId, category, category, title, description, details);
    }

    /**
     * Records that happen during an HTTP request — pulls the request
     * off the thread-local automatically for device/IP capture.
     */
    public void record(Long userId, String recordType, String category,
                       String title, String description,
                       Map<String, Object> details) {
        record(userId, recordType, category, title, description, details, currentRequest());
    }

    /**
     * Use this overload when you have a request reference handy or
     * intentionally want to skip request capture (background jobs).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String recordType, String category,
                       String title, String description,
                       Map<String, Object> details,
                       HttpServletRequest request) {
        try {
            String json = null;
            if (details != null && !details.isEmpty()) {
                try {
                    json = objectMapper.writeValueAsString(details);
                } catch (JsonProcessingException e) {
                    log.warn("Couldn't serialize record details for type={}, falling back to toString", recordType);
                    json = details.toString();
                }
            }

            UserRecord rec = UserRecord.builder()
                    .userId(userId)
                    .recordType(recordType)
                    .category(category)
                    .title(safeTrim(title, 255))
                    .description(description != null ? description : "")
                    .details(json)
                    .build();

            if (request != null) {
                rec.setIpAddress(safeTrim(getClientIp(request), 45));
                String ua = request.getHeader("User-Agent");
                rec.setBrowser(safeTrim(parseBrowser(ua), 100));
                rec.setOs(safeTrim(parseOs(ua), 50));
                rec.setDeviceType(safeTrim(parseDeviceType(ua), 20));
            }

            recordRepository.save(rec);
        } catch (Exception e) {
            log.error("Failed to write user record (type={}, userId={})", recordType, userId, e);
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Walks X-Forwarded-For first (Railway / typical proxy chain) then
     * falls back to the direct remote addr. Returns the first non-empty
     * value because the leftmost forwarded entry is the original client.
     */
    private String getClientIp(HttpServletRequest req) {
        String[] headers = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR"
        };
        for (String h : headers) {
            String value = req.getHeader(h);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return req.getRemoteAddr();
    }

    private String parseBrowser(String ua) {
        if (ua == null) return null;
        String s = ua.toLowerCase();
        if (s.contains("edg/")) return "Edge";
        if (s.contains("chrome/") && !s.contains("chromium")) return "Chrome";
        if (s.contains("firefox/")) return "Firefox";
        if (s.contains("safari/") && !s.contains("chrome")) return "Safari";
        if (s.contains("opera") || s.contains("opr/")) return "Opera";
        return "Other";
    }

    private String parseOs(String ua) {
        if (ua == null) return null;
        String s = ua.toLowerCase();
        if (s.contains("windows nt 10")) return "Windows 10/11";
        if (s.contains("windows")) return "Windows";
        if (s.contains("mac os x") || s.contains("macintosh")) return "macOS";
        if (s.contains("android")) return "Android";
        if (s.contains("iphone") || s.contains("ipad") || s.contains("ipod")) return "iOS";
        if (s.contains("linux")) return "Linux";
        return "Other";
    }

    private String parseDeviceType(String ua) {
        if (ua == null) return null;
        String s = ua.toLowerCase();
        if (s.contains("ipad") || s.contains("tablet")) return "Tablet";
        if (s.contains("mobile") || s.contains("iphone") || s.contains("android")) return "Mobile";
        return "Desktop";
    }

    private String safeTrim(String v, int max) {
        if (v == null) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }
}

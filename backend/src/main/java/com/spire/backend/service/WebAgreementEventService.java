package com.spire.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.repository.WebAgreementEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The website agreement's audit log. Same shape as the console's
 * {@code appendEvent}, but every row carries the real users.id of whoever
 * acted, plus the caller's IP (the proxy-appended last X-Forwarded-For hop)
 * and user agent when there is a request.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementEventService {

    private final WebAgreementEventRepository eventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Appends one event. {@code request} may be null (system-side events such as EMAIL_SENT). */
    public WebAgreementEvent append(Long agreementId,
                                    WebAgreementEvent.EventType type,
                                    WebAgreementEvent.ActorType actorType,
                                    Long actorUserId,
                                    Map<String, Object> metadata,
                                    HttpServletRequest request) {
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(
                    metadata == null ? new LinkedHashMap<String, Object>() : metadata);
        } catch (Exception e) {
            metaJson = "{}";
        }
        String ip = request == null ? null : AcknowledgmentService.clientIp(request);
        String ua = request == null ? null : request.getHeader("User-Agent");

        WebAgreementEvent event = WebAgreementEvent.builder()
                .agreementId(agreementId)
                .eventType(type.name())
                .actorType(actorType.name())
                .actorUserId(actorUserId)
                .metadata(metaJson)
                .ipAddress(ip)
                .userAgent(ua)
                .build();
        return eventRepository.save(event);
    }

    /** Every event of one agreement, newest first (the ERM timeline). */
    public List<WebAgreementEvent> list(Long agreementId) {
        return eventRepository.findByAgreementIdOrderByCreatedAtDesc(agreementId);
    }

    /**
     * Has the participant filled or uploaded anything since {@code since}?
     * Opening the page doesn't count, only the writes in
     * {@link WebAgreementRules#CONSULTANT_WORK_EVENTS}. False when
     * {@code since} is null.
     */
    public boolean participantActedSince(Long agreementId, LocalDateTime since) {
        if (since == null) return false;
        return !eventRepository.findByAgreementIdAndEventTypeInAndCreatedAtAfter(
                agreementId, WebAgreementRules.CONSULTANT_WORK_EVENTS, since).isEmpty();
    }
}

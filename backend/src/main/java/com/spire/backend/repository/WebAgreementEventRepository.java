package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreementEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Website agreement audit log. {@code eventType} is a plain String column:
 * pass {@code EventType.X.name()}, never the enum (Hibernate 6 throws).
 */
@Repository
public interface WebAgreementEventRepository extends JpaRepository<WebAgreementEvent, Long> {

    List<WebAgreementEvent> findByAgreementIdOrderByCreatedAtDesc(Long agreementId);

    List<WebAgreementEvent> findByAgreementIdAndEventTypeOrderByCreatedAtDesc(
            Long agreementId, String eventType);

    /** Events of the given types after a moment (the take-back guard). */
    List<WebAgreementEvent> findByAgreementIdAndEventTypeInAndCreatedAtAfter(
            Long agreementId, Collection<String> eventTypes, LocalDateTime after);

    /** One event type across a page of agreements (list columns). */
    List<WebAgreementEvent> findByAgreementIdInAndEventType(
            Collection<Long> agreementIds, String eventType);
}

package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantApplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConsultantApplicationEventRepository
        extends JpaRepository<ConsultantApplicationEvent, Long> {

    List<ConsultantApplicationEvent> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);

    /**
     * Build O — events of a given type across a page of applications,
     * batched for the ERM "All" list (drives the "Agreement Sent On"
     * column from {@code SENT_FOR_APPROVAL} events). NOTE: {@code eventType}
     * is a plain String column (stored via {@code type.name()}), so the
     * parameter is a String — pass {@code EventType.X.name()}, never the
     * enum (Hibernate 6 throws binding an enum to a String attribute).
     */
    List<ConsultantApplicationEvent> findByApplicationIdInAndEventType(
            java.util.Collection<Long> applicationIds,
            String eventType);
}

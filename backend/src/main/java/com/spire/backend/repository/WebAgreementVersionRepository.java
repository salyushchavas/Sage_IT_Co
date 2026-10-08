package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreementVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Immutable verified-version snapshots of website agreements (V1, V2, …). Rows are never updated or deleted. */
@Repository
public interface WebAgreementVersionRepository extends JpaRepository<WebAgreementVersion, Long> {

    /** All versions of an agreement, oldest first (V1 first). */
    List<WebAgreementVersion> findByAgreementIdOrderByVersionNumberAsc(Long agreementId);

    /** The highest version (to compute the next number, and "latest"). */
    Optional<WebAgreementVersion> findTopByAgreementIdOrderByVersionNumberDesc(Long agreementId);

    /** One version (the version PDF, and the send-for-approval pick). */
    Optional<WebAgreementVersion> findByAgreementIdAndVersionNumber(Long agreementId, Integer versionNumber);
}

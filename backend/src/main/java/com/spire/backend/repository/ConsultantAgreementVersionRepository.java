package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantAgreementVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Build V — immutable approved-consultant-version snapshots (V1, V2, …). */
@Repository
public interface ConsultantAgreementVersionRepository
        extends JpaRepository<ConsultantAgreementVersion, Long> {

    /** All versions for an application, oldest → newest (V1 first). */
    List<ConsultantAgreementVersion> findByApplicationIdOrderByVersionNumberAsc(Long applicationId);

    /** The highest existing version (to compute the next number). */
    Optional<ConsultantAgreementVersion> findTopByApplicationIdOrderByVersionNumberDesc(Long applicationId);

    /** A specific version (for preview / send-for-approval selection). */
    Optional<ConsultantAgreementVersion> findByApplicationIdAndVersionNumber(
            Long applicationId, Integer versionNumber);
}

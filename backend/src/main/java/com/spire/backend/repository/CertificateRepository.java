package com.spire.backend.repository;

import com.spire.backend.entity.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    Optional<Certificate> findByUserIdAndCourseId(Long userId, Long courseId);
    boolean existsByUserIdAndCourseId(Long userId, Long courseId);
    List<Certificate> findByUserId(Long userId);
    Optional<Certificate> findByCertificateId(String certificateId);

    /** Phase 1 (S3) — resolve a cert from its stored certificate_url so the
     *  legacy no-auth /download/{courseId}/{fileName} endpoint can recover the
     *  S3 key for certificates issued after the S3 cutover. */
    Optional<Certificate> findByCertificateUrl(String certificateUrl);
}

package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Website agreements. Status is a plain String column: pass
 * {@code WebAgreement.Status.X.name()}, never the enum.
 */
@Repository
public interface WebAgreementRepository extends JpaRepository<WebAgreement, Long> {

    Optional<WebAgreement> findByApplicationId(String applicationId);

    /** A participant's live agreements, newest first (archived rows excluded). */
    List<WebAgreement> findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(Long participantUserId);

    /**
     * A participant's live agreements as a locking read (SELECT ... FOR
     * UPDATE): the create's duplicate check, which must see an agreement
     * another ERM's create committed a moment ago.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<WebAgreement> findForUpdateByParticipantUserIdAndDeletedFalse(Long participantUserId);

    /** Live agreements of many participants at once (the "ready for an agreement" list). */
    List<WebAgreement> findByParticipantUserIdInAndDeletedFalse(Collection<Long> participantUserIds);

    // ERM list: owner-scoped.
    Page<WebAgreement> findByOwnerUserIdAndDeletedFalse(Long ownerUserId, Pageable pageable);

    Page<WebAgreement> findByOwnerUserIdAndStatusAndDeletedFalse(
            Long ownerUserId, String status, Pageable pageable);

    // Admin list (OPERATIONS_ADMIN / SYSTEM_ADMIN): every owner.
    Page<WebAgreement> findByDeletedFalse(Pageable pageable);

    Page<WebAgreement> findByStatusAndDeletedFalse(String status, Pageable pageable);

    /**
     * The staff list with a search and the "verified by the ERM" split.
     * Every filter is optional (null = any): ownerUserId scopes an ERM to
     * their own rows (null for admins), released only applies to VERIFIED,
     * and q is a lower-cased LIKE pattern (with '!' as its escape) matched
     * against the participant's email and name and the agreement id.
     */
    @Query("""
            SELECT a FROM WebAgreement a
            WHERE a.deleted = false
              AND (:ownerUserId IS NULL OR a.ownerUserId = :ownerUserId)
              AND (:status IS NULL OR a.status = :status)
              AND (:released IS NULL OR a.consultantCopyReleased = :released)
              AND (:q IS NULL
                   OR LOWER(a.consultantEmail) LIKE :q ESCAPE '!'
                   OR LOWER(a.consultantName) LIKE :q ESCAPE '!'
                   OR LOWER(a.applicationId) LIKE :q ESCAPE '!')
            """)
    Page<WebAgreement> searchForStaff(@Param("ownerUserId") Long ownerUserId,
                                      @Param("status") String status,
                                      @Param("released") Boolean released,
                                      @Param("q") String q,
                                      Pageable pageable);
}

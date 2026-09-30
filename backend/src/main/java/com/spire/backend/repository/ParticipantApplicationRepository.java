package com.spire.backend.repository;

import com.spire.backend.entity.ParticipantApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipantApplicationRepository extends JpaRepository<ParticipantApplication, Long> {

    /** The open application for an email (waiting for an ERM, or confirmed and not registered yet). */
    Optional<ParticipantApplication> findFirstByEmailAndStatusInOrderByCreatedAtDesc(String email, List<String> statuses);

    Optional<ParticipantApplication> findByRegistrationTokenHash(String registrationTokenHash);

    List<ParticipantApplication> findTop500ByStatusOrderByCreatedAtAsc(String status);

    List<ParticipantApplication> findTop500ByOrderByCreatedAtDesc();

    long countByStatus(String status);

    /**
     * The application a registration link belongs to, locked until the
     * transaction ends. Registering clears the hash, so a second click on
     * the same link waits here and then finds nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ParticipantApplication a where a.registrationTokenHash = :hash")
    Optional<ParticipantApplication> findByRegistrationTokenHashForUpdate(@Param("hash") String hash);
}

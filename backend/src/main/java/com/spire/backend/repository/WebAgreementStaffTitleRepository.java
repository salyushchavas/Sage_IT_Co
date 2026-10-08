package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreementStaffTitle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Staff titles printed on agreements, one row per user. */
@Repository
public interface WebAgreementStaffTitleRepository extends JpaRepository<WebAgreementStaffTitle, Long> {

    Optional<WebAgreementStaffTitle> findByUserId(Long userId);
}

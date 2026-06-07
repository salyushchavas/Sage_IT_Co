package com.spire.backend.repository;

import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the consultant-agreement console's user
 * directory ({@link AgreementUser}). PK is a String UUID.
 */
public interface AgreementUserRepository extends JpaRepository<AgreementUser, String> {

    Optional<AgreementUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AgreementUser> findByRole(AgreementUserRole role);
}

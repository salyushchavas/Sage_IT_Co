package com.spire.backend.repository;

import com.spire.backend.entity.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByUserIdOrderByIssueDateDesc(Long userId);
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
    boolean existsByInvoiceNumber(String invoiceNumber);
    List<Invoice> findByPaymentPlanId(Long paymentPlanId);
    List<Invoice> findByStatusOrderByDueDateAsc(String status);

    /**
     * The invoice, locked until the transaction ends. Ledger entries on one
     * invoice then run one at a time, so two simultaneous payments can't both
     * pass the balance check and two reversals can't both undo one payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);
}

package com.spire.backend.repository;

import com.spire.backend.entity.PaymentLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentLedgerRepository extends JpaRepository<PaymentLedger, Long> {
    List<PaymentLedger> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<PaymentLedger> findByInvoiceIdOrderByCreatedAtAsc(Long invoiceId);
}

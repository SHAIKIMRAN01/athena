package com.athena.repository;

import com.athena.entity.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>,
                JpaSpecificationExecutor<FinancialTransaction> {

    Optional<FinancialTransaction> findByCreatedByIdAndIdempotencyKey(
            Long userId, String idempotencyKey);
}

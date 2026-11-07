package com.ledgerline.repository;

import com.ledgerline.entity.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long> {
}

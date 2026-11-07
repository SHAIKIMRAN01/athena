package com.ledgerline.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AccountResponse {

    private String accountNumber;
    private BigDecimal balance;
    private String status;
    private LocalDateTime createdAt;

    public AccountResponse(
            String accountNumber,
            BigDecimal balance,
            String status,
            LocalDateTime createdAt) {

        this.accountNumber = accountNumber;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
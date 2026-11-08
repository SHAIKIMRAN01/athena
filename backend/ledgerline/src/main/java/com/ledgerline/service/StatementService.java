package com.ledgerline.service;

import com.ledgerline.dto.StatementLine;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.entity.Account;
import com.ledgerline.entity.EntryType;
import com.ledgerline.entity.LedgerEntry;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InvalidRequestException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Account statements, built from the ledger: the opening balance is every
 * entry before the period, and each line carries the balance after it.
 * Only completed transactions have ledger entries, so FAILED attempts never
 * appear on a statement.
 */
@Service
public class StatementService {

    static final int MAX_DAYS = 366;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public StatementService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository) {

        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Defaults: {@code from} is the first day of this month, {@code to} is today.
     */
    @Transactional(readOnly = true)
    public StatementResponse statement(Long userId, LocalDate from, LocalDate to) {

        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;

        if (start.isAfter(end)) {
            throw new InvalidRequestException("INVALID_DATE_RANGE", "from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new InvalidRequestException(
                    "STATEMENT_RANGE_TOO_LONG", "A statement can cover at most " + MAX_DAYS + " days");
        }

        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("You do not have an account"));

        LocalDateTime periodStart = start.atStartOfDay();
        LocalDateTime periodEnd = end.plusDays(1).atStartOfDay();

        // Customer accounts: credits add to the balance, debits subtract.
        BigDecimal openingBalance = money(
                ledgerEntryRepository.sumBefore(account.getId(), EntryType.CREDIT, periodStart)
                        .subtract(ledgerEntryRepository.sumBefore(
                                account.getId(), EntryType.DEBIT, periodStart)));

        BigDecimal balance = openingBalance;
        BigDecimal totalCredits = money(BigDecimal.ZERO);
        BigDecimal totalDebits = money(BigDecimal.ZERO);
        List<StatementLine> lines = new ArrayList<>();

        for (LedgerEntry entry : ledgerEntryRepository.findStatementLines(
                account.getId(), periodStart, periodEnd)) {

            if (entry.getEntryType() == EntryType.CREDIT) {
                totalCredits = totalCredits.add(entry.getAmount());
                balance = balance.add(entry.getAmount());
            } else {
                totalDebits = totalDebits.add(entry.getAmount());
                balance = balance.subtract(entry.getAmount());
            }
            lines.add(StatementLine.of(entry, balance));
        }

        return new StatementResponse(
                account.getAccountNumber(),
                start,
                end,
                openingBalance,
                totalCredits,
                totalDebits,
                balance,
                lines);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2);
    }
}

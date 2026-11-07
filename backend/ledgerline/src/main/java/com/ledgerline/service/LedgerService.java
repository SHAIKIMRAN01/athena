package com.ledgerline.service;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.EntryType;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.LedgerEntry;
import com.ledgerline.exception.BalanceLimitExceededException;
import com.ledgerline.repository.LedgerEntryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Posts money movements to the double-entry ledger.
 *
 * <p>Every movement is one DEBIT and one CREDIT of the same amount, so total
 * debits always equal total credits. This is the only code that changes account
 * balances, and it changes them from the same entries it records, so every
 * balance can be re-derived from the ledger.
 */
@Service
public class LedgerService {

    /** Largest value a NUMERIC(19,2) column can hold. */
    private static final BigDecimal MAX_BALANCE =
            new BigDecimal("99999999999999999.99");

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Moves {@code amount} from {@code debitAccount} to {@code creditAccount}.
     * Both accounts must already be locked by the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(
            FinancialTransaction transaction,
            Account debitAccount,
            Account creditAccount,
            BigDecimal amount) {

        if (amount == null || amount.signum() <= 0 || amount.scale() != 2) {
            throw new IllegalArgumentException(
                    "Ledger amounts must be positive with exactly 2 decimal places");
        }

        if (debitAccount.getId().equals(creditAccount.getId())) {
            throw new IllegalArgumentException(
                    "Debit and credit accounts must be different");
        }

        applyEntry(debitAccount, EntryType.DEBIT, amount);
        applyEntry(creditAccount, EntryType.CREDIT, amount);

        ledgerEntryRepository.save(
                new LedgerEntry(transaction, debitAccount, EntryType.DEBIT, amount));
        ledgerEntryRepository.save(
                new LedgerEntry(transaction, creditAccount, EntryType.CREDIT, amount));
    }

    private static void applyEntry(
            Account account,
            EntryType entryType,
            BigDecimal amount) {

        boolean increasesBalance =
                entryType == account.getAccountType().getNormalBalance();

        BigDecimal newBalance = increasesBalance
                ? account.getBalance().add(amount)
                : account.getBalance().subtract(amount);

        if (newBalance.signum() < 0) {
            // Callers check available funds before posting, so getting here is a
            // bug. Refuse instead of overdrawing; the transaction rolls back.
            throw new IllegalStateException(
                    "Posting would make account " + account.getId() + " negative");
        }

        if (newBalance.compareTo(MAX_BALANCE) > 0) {
            throw new BalanceLimitExceededException();
        }

        account.setBalance(newBalance);
    }
}

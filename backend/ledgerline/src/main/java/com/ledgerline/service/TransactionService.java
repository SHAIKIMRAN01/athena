package com.ledgerline.service;

import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountType;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.TransactionType;
import com.ledgerline.exception.AccountNotActiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidTransactionException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.FinancialTransactionRepository;
import com.ledgerline.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.LongStream;

/**
 * Deposits, withdrawals and transfers.
 *
 * <p>Each operation runs in one database transaction that:
 * <ol>
 *   <li>locks every account it touches, always in ascending id order, so
 *       concurrent operations wait for each other instead of overwriting each
 *       other's balances, and can never deadlock;</li>
 *   <li>checks the business rules against the locked, current balances;</li>
 *   <li>records the transaction and posts balanced ledger entries.</li>
 * </ol>
 * Any exception rolls the whole operation back: no balance change, no
 * transaction record and no ledger entries are left behind.
 */
@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;

    public TransactionService(
            AccountRepository accountRepository,
            FinancialTransactionRepository transactionRepository,
            UserRepository userRepository,
            LedgerService ledgerService) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Cash in: DEBIT the settlement account, CREDIT the user's account.
     */
    @Transactional
    public TransactionResponse deposit(Long userId, BigDecimal requestedAmount) {

        BigDecimal amount = toMoney(requestedAmount);
        long customerId = ownAccountId(userId);
        long settlementId = settlementAccountId();

        Map<Long, Account> accounts = lockInIdOrder(customerId, settlementId);
        Account customer = accounts.get(customerId);
        Account settlement = accounts.get(settlementId);

        requireActive(customer, "Your account is not active");
        requireActive(settlement, "Deposits and withdrawals are currently unavailable");

        FinancialTransaction transaction = record(
                TransactionType.DEPOSIT, null, customer, amount, userId);
        ledgerService.post(transaction, settlement, customer, amount);

        return TransactionResponse.of(transaction, customer);
    }

    /**
     * Cash out: DEBIT the user's account, CREDIT the settlement account.
     */
    @Transactional
    public TransactionResponse withdraw(Long userId, BigDecimal requestedAmount) {

        BigDecimal amount = toMoney(requestedAmount);
        long customerId = ownAccountId(userId);
        long settlementId = settlementAccountId();

        Map<Long, Account> accounts = lockInIdOrder(customerId, settlementId);
        Account customer = accounts.get(customerId);
        Account settlement = accounts.get(settlementId);

        requireActive(customer, "Your account is not active");
        requireActive(settlement, "Deposits and withdrawals are currently unavailable");
        requireFunds(customer, amount);

        FinancialTransaction transaction = record(
                TransactionType.WITHDRAWAL, customer, null, amount, userId);
        ledgerService.post(transaction, customer, settlement, amount);

        return TransactionResponse.of(transaction, customer);
    }

    /**
     * DEBIT the user's account, CREDIT the receiver's account.
     */
    @Transactional
    public TransactionResponse transfer(
            Long userId,
            String toAccountNumber,
            BigDecimal requestedAmount) {

        BigDecimal amount = toMoney(requestedAmount);
        long senderId = ownAccountId(userId);
        long receiverId = accountRepository
                .findIdByAccountNumberAndAccountType(
                        normalizeAccountNumber(toAccountNumber),
                        AccountType.CUSTOMER)
                .orElseThrow(() ->
                        new AccountNotFoundException("Receiver account not found"));

        if (senderId == receiverId) {
            throw new InvalidTransactionException(
                    "SAME_ACCOUNT_TRANSFER",
                    "You cannot transfer money to your own account");
        }

        Map<Long, Account> accounts = lockInIdOrder(senderId, receiverId);
        Account sender = accounts.get(senderId);
        Account receiver = accounts.get(receiverId);

        requireActive(sender, "Your account is not active");
        requireActive(receiver, "Receiver account is not active");
        requireFunds(sender, amount);

        FinancialTransaction transaction = record(
                TransactionType.TRANSFER, sender, receiver, amount, userId);
        ledgerService.post(transaction, sender, receiver, amount);

        return TransactionResponse.of(transaction, sender);
    }

    private FinancialTransaction record(
            TransactionType type,
            Account fromAccount,
            Account toAccount,
            BigDecimal amount,
            Long userId) {

        return transactionRepository.save(FinancialTransaction.completed(
                type,
                fromAccount,
                toAccount,
                amount,
                userRepository.getReferenceById(userId)));
    }

    /**
     * Locks the accounts (SELECT ... FOR UPDATE) in ascending id order.
     * Because every operation locks in the same order, two operations can
     * never each hold a lock the other is waiting for.
     */
    private Map<Long, Account> lockInIdOrder(long... accountIds) {

        Map<Long, Account> locked = new HashMap<>();

        for (long id : LongStream.of(accountIds).sorted().distinct().toArray()) {
            locked.put(id, accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found")));
        }

        return locked;
    }

    private long ownAccountId(Long userId) {
        return accountRepository.findIdByUserId(userId)
                .orElseThrow(() ->
                        new AccountNotFoundException("You do not have an account"));
    }

    private long settlementAccountId() {
        return accountRepository.findIdByAccountType(AccountType.SETTLEMENT)
                .orElseThrow(() -> new IllegalStateException(
                        "Settlement account is missing; it is created by db/seed.sql"));
    }

    private static void requireActive(Account account, String message) {
        if (!account.isActive()) {
            throw new AccountNotActiveException(message);
        }
    }

    private static void requireFunds(Account account, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
    }

    /**
     * Checks an amount and gives it exactly 2 decimal places. Never rounds:
     * an amount with more decimals is rejected.
     */
    private static BigDecimal toMoney(BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransactionException(
                    "INVALID_AMOUNT", "Amount must be greater than zero");
        }

        BigDecimal money;
        try {
            money = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException moreThanTwoDecimals) {
            throw new InvalidTransactionException(
                    "INVALID_AMOUNT", "Amount cannot have more than 2 decimal places");
        }

        if (money.precision() > 19) {
            throw new InvalidTransactionException(
                    "INVALID_AMOUNT", "Amount is too large");
        }

        return money;
    }

    private static String normalizeAccountNumber(String accountNumber) {
        return accountNumber == null
                ? ""
                : accountNumber.trim().toUpperCase(Locale.ROOT);
    }
}

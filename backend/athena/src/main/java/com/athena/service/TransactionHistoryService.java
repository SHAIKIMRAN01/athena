package com.athena.service;

import com.athena.dto.PageResponse;
import com.athena.dto.TransactionDetail;
import com.athena.dto.TransactionHistoryItem;
import com.athena.entity.Account;
import com.athena.entity.FinancialTransaction;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;
import com.athena.exception.AccountNotFoundException;
import com.athena.exception.ResourceNotFoundException;
import com.athena.repository.AccountRepository;
import com.athena.repository.FinancialTransactionRepository;
import com.athena.repository.LedgerEntryRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The caller's transaction history, newest first by default, and the details
 * of one transaction.
 *
 * <p>COMPLETED transactions appear for both the sender and the receiver. A
 * FAILED attempt appears only for the user who made it, so nobody sees other
 * people's refused transfers to them. A transaction the caller may not see
 * answers 404, exactly like one that does not exist.
 */
@Service
public class TransactionHistoryService {

    static final int MAX_PAGE_SIZE = QuerySupport.MAX_PAGE_SIZE;

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransactionHistoryService(
            AccountRepository accountRepository,
            FinancialTransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public PageResponse<TransactionHistoryItem> history(
            Long userId,
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String sort) {

        return history(userId, type, status, from, to, null, page, size, sort);
    }

    /**
     * @param search a reference such as TXN0000000042, or the other party's account number
     * @param sort "createdAt" or "amount", optionally followed by ",asc" or ",desc"
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionHistoryItem> history(
            Long userId,
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to,
            String search,
            int page,
            int size,
            String sort) {

        QuerySupport.checkPage(page, size);
        QuerySupport.checkDateRange(from, to);
        Optional<String> term = QuerySupport.searchTerm(search);

        long accountId = ownAccountId(userId);

        Specification<FinancialTransaction> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(visibleTo(root, cb, accountId, userId));

            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            }
            term.ifPresent(text -> {
                Optional<Long> id = QuerySupport.referenceId(text);
                if (id.isPresent()) {
                    predicates.add(cb.equal(root.get("id"), id.get()));
                } else {
                    String accountNumber = text.toUpperCase(Locale.ROOT);
                    Join<FinancialTransaction, Account> fromAccount = root.join("fromAccount", JoinType.LEFT);
                    Join<FinancialTransaction, Account> toAccount = root.join("toAccount", JoinType.LEFT);
                    predicates.add(cb.or(
                            cb.equal(fromAccount.get("accountNumber"), accountNumber),
                            cb.equal(toAccount.get("accountNumber"), accountNumber)));
                }
            });

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return PageResponse.of(transactionRepository
                .findAll(filter, PageRequest.of(page, size, QuerySupport.transactionSort(sort)))
                .map(transaction -> TransactionHistoryItem.of(transaction, accountId)));
    }

    /**
     * One transaction of the caller's, with its ledger entries.
     */
    @Transactional(readOnly = true)
    public TransactionDetail detail(Long userId, Long transactionId) {

        long accountId = ownAccountId(userId);

        Specification<FinancialTransaction> mine = (root, query, cb) -> cb.and(
                cb.equal(root.get("id"), transactionId),
                visibleTo(root, cb, accountId, userId));

        FinancialTransaction transaction = transactionRepository.findOne(mine)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TRANSACTION_NOT_FOUND", "Transaction not found"));

        List<TransactionDetail.Line> lines = ledgerEntryRepository
                .findByTransactionWithAccounts(transaction.getId())
                .stream()
                .map(entry -> new TransactionDetail.Line(
                        entry.getEntryType().name(),
                        entry.getAccount().getAccountNumber(),
                        entry.getAmount()))
                .toList();

        return new TransactionDetail(TransactionHistoryItem.of(transaction, accountId), lines);
    }

    /**
     * The caller's account is on one side, and the transaction either
     * completed or was attempted by the caller.
     */
    private static Predicate visibleTo(
            jakarta.persistence.criteria.Root<FinancialTransaction> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            long accountId,
            Long userId) {

        return cb.and(
                cb.or(
                        cb.equal(root.get("fromAccount").get("id"), accountId),
                        cb.equal(root.get("toAccount").get("id"), accountId)),
                cb.or(
                        cb.equal(root.get("status"), TransactionStatus.COMPLETED),
                        cb.equal(root.get("createdBy").get("id"), userId)));
    }

    private long ownAccountId(Long userId) {
        return accountRepository.findIdByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("You do not have an account"));
    }
}

package com.ledgerline.service;

import com.ledgerline.dto.PageResponse;
import com.ledgerline.dto.TransactionHistoryItem;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InvalidRequestException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.FinancialTransactionRepository;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The caller's transaction history, newest first by default.
 *
 * <p>COMPLETED transactions appear for both the sender and the receiver. A
 * FAILED attempt appears only for the user who made it, so nobody sees other
 * people's refused transfers to them.
 */
@Service
public class TransactionHistoryService {

    static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "amount");

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;

    public TransactionHistoryService(
            AccountRepository accountRepository,
            FinancialTransactionRepository transactionRepository) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * @param sort "createdAt" or "amount", optionally followed by ",asc" or ",desc"
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionHistoryItem> history(
            Long userId,
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String sort) {

        if (page < 0) {
            throw new InvalidRequestException("INVALID_PAGE", "page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException(
                    "INVALID_DATE_RANGE", "from must be on or before to");
        }

        long accountId = accountRepository.findIdByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("You do not have an account"));

        Specification<FinancialTransaction> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.or(
                    cb.equal(root.get("fromAccount").get("id"), accountId),
                    cb.equal(root.get("toAccount").get("id"), accountId)));

            predicates.add(cb.or(
                    cb.equal(root.get("status"), TransactionStatus.COMPLETED),
                    cb.equal(root.get("createdBy").get("id"), userId)));

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

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return PageResponse.of(transactionRepository
                .findAll(filter, PageRequest.of(page, size, parseSort(sort)))
                .map(transaction -> TransactionHistoryItem.of(transaction, accountId)));
    }

    private static Sort parseSort(String sort) {

        String[] parts = (sort == null || sort.isBlank() ? "createdAt,desc" : sort).split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "desc";

        if (parts.length > 2 || !SORTABLE_FIELDS.contains(field)
                || !(direction.equals("asc") || direction.equals("desc"))) {
            throw new InvalidRequestException("INVALID_SORT",
                    "sort must be createdAt or amount, optionally followed by ,asc or ,desc");
        }

        Sort.Direction order = Sort.Direction.fromString(direction);
        // The id breaks ties, so paging through equal timestamps or amounts is stable.
        return Sort.by(order, field).and(Sort.by(order, "id"));
    }
}

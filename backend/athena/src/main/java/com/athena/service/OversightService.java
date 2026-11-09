package com.athena.service;

import com.athena.dto.AuditLogView;
import com.athena.dto.MonitoredTransaction;
import com.athena.dto.PageResponse;
import com.athena.entity.Account;
import com.athena.entity.AuditLog;
import com.athena.entity.AuditOutcome;
import com.athena.entity.FinancialTransaction;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;
import com.athena.entity.User;
import com.athena.repository.AuditLogRepository;
import com.athena.repository.FinancialTransactionRepository;
import com.athena.repository.UserRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only views across all customers, for administrators and auditors:
 * every transaction (transaction monitoring) and the audit log.
 */
@Service
public class OversightService {

    private final FinancialTransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public OversightService(
            FinancialTransactionRepository transactionRepository,
            AuditLogRepository auditLogRepository,
            UserRepository userRepository) {

        this.transactionRepository = transactionRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param account an account number on either side of the transaction
     * @param search a reference such as TXN0000000042
     */
    @Transactional(readOnly = true)
    public PageResponse<MonitoredTransaction> transactions(
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to,
            String account,
            String search,
            int page,
            int size,
            String sort) {

        QuerySupport.checkPage(page, size);
        QuerySupport.checkDateRange(from, to);
        Optional<String> accountNumber = QuerySupport.searchTerm(account)
                .map(text -> text.toUpperCase(Locale.ROOT));
        Optional<String> term = QuerySupport.searchTerm(search);

        Specification<FinancialTransaction> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

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
            accountNumber.ifPresent(number -> {
                Join<FinancialTransaction, Account> fromAccount = root.join("fromAccount", JoinType.LEFT);
                Join<FinancialTransaction, Account> toAccount = root.join("toAccount", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(fromAccount.get("accountNumber"), number),
                        cb.equal(toAccount.get("accountNumber"), number)));
            });
            term.ifPresent(text -> predicates.add(QuerySupport.referenceId(text)
                    .map(id -> cb.equal(root.get("id"), id))
                    // Not a reference: match nothing rather than guess.
                    .orElse(cb.disjunction())));

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return PageResponse.of(transactionRepository
                .findAll(filter, PageRequest.of(page, size, QuerySupport.transactionSort(sort)))
                .map(MonitoredTransaction::of));
    }

    /**
     * @param action for example DEPOSIT, LOGIN, ACCESS_DENIED, ACCOUNT_FROZEN
     * @param actorEmail only records by this user
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogView> auditLogs(
            String action,
            AuditOutcome outcome,
            String actorEmail,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {

        QuerySupport.checkPage(page, size);
        QuerySupport.checkDateRange(from, to);
        Optional<String> actionName = QuerySupport.searchTerm(action)
                .map(text -> text.toUpperCase(Locale.ROOT));
        Optional<String> email = QuerySupport.searchTerm(actorEmail).map(AuthService::normalizeEmail);

        Optional<Long> actorId = email.map(address -> userRepository.findByEmail(address)
                .map(User::getId)
                // An unknown email matches no records.
                .orElse(-1L));

        Specification<AuditLog> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            actionName.ifPresent(name -> predicates.add(cb.equal(root.get("action"), name)));
            if (outcome != null) {
                predicates.add(cb.equal(root.get("outcome"), outcome));
            }
            actorId.ifPresent(id -> predicates.add(cb.equal(root.get("actorId"), id)));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<AuditLog> records = auditLogRepository.findAll(filter, PageRequest.of(page, size, newestFirst));

        Set<Long> actorIds = records.stream()
                .map(AuditLog::getActorId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> emails = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail, (a, b) -> a));

        Function<AuditLog, AuditLogView> toView = record -> new AuditLogView(
                record.getId(),
                record.getActorId(),
                record.getActorId() == null ? null : emails.get(record.getActorId()),
                record.getAction(),
                record.getOutcome(),
                record.getEntityType(),
                record.getEntityId(),
                record.getDetails(),
                record.getCreatedAt());

        return PageResponse.of(records.map(toView));
    }
}

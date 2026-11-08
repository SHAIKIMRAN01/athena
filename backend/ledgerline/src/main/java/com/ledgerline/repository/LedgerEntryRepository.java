package com.ledgerline.repository;

import com.ledgerline.entity.EntryType;
import com.ledgerline.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Balance of a customer account right after the given transaction, rebuilt
     * from the ledger (credits add, debits subtract). Postings to one account
     * happen while its row is locked, so its transaction ids increase in the
     * order the postings happened.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries
            WHERE account_id = :accountId AND transaction_id <= :transactionId
            """, nativeQuery = true)
    BigDecimal customerBalanceAfter(
            @Param("accountId") Long accountId,
            @Param("transactionId") Long transactionId);

    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.account.id = :accountId and e.entryType = :entryType
              and e.createdAt < :before
            """)
    BigDecimal sumBefore(
            @Param("accountId") Long accountId,
            @Param("entryType") EntryType entryType,
            @Param("before") LocalDateTime before);

    /**
     * An account's ledger lines in a period, oldest first, with their
     * transaction and its accounts loaded for the statement.
     */
    @Query("""
            select e from LedgerEntry e
            join fetch e.transaction t
            left join fetch t.fromAccount
            left join fetch t.toAccount
            where e.account.id = :accountId
              and e.createdAt >= :from and e.createdAt < :to
            order by e.id
            """)
    List<LedgerEntry> findStatementLines(
            @Param("accountId") Long accountId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}

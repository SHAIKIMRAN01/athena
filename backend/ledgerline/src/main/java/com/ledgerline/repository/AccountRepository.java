package com.ledgerline.repository;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserId(Long userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    // The id lookups below return ids only, so no Account entity is loaded before
    // findByIdForUpdate locks it. An entity loaded earlier in the same transaction
    // would keep its old, unlocked balance.

    @Query("select a.id from Account a where a.user.id = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    @Query("""
            select a.id from Account a
            where a.accountNumber = :accountNumber and a.accountType = :accountType
            """)
    Optional<Long> findIdByAccountNumberAndAccountType(
            @Param("accountNumber") String accountNumber,
            @Param("accountType") AccountType accountType);

    @Query("select a.id from Account a where a.accountType = :accountType")
    Optional<Long> findIdByAccountType(@Param("accountType") AccountType accountType);

    /**
     * Loads an account with a row lock (SELECT ... FOR UPDATE) held until the
     * surrounding transaction ends. Concurrent transactions touching the same
     * account wait here, then read the balance the previous one committed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}

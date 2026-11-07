package com.ledgerline.entity;

/**
 * What an account represents in the double-entry ledger.
 *
 * <p>LedgerLine keeps its books from the bank's point of view:
 * <ul>
 *   <li>{@link #CUSTOMER} accounts hold money the bank owes its customers (a liability),
 *       so a CREDIT increases their balance and a DEBIT decreases it.</li>
 *   <li>The single {@link #SETTLEMENT} account is the bank's own cash (an asset). It is the
 *       other side of every deposit and withdrawal, so a DEBIT increases its balance and a
 *       CREDIT decreases it. Its balance therefore always equals the sum of all customer
 *       balances.</li>
 * </ul>
 */
public enum AccountType {

    CUSTOMER(EntryType.CREDIT),
    SETTLEMENT(EntryType.DEBIT);

    private final EntryType normalBalance;

    AccountType(EntryType normalBalance) {
        this.normalBalance = normalBalance;
    }

    /**
     * The entry type that increases the balance of an account of this type.
     */
    public EntryType getNormalBalance() {
        return normalBalance;
    }
}

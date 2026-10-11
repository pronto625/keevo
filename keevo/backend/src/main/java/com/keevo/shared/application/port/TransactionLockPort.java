package com.keevo.shared.application.port;

/**
 * TransactionLockPort — transaction-scoped mutual exclusion keyed by a string (Story 13.9).
 *
 * <p>Used to serialise check-then-act sections (plan limit count → save, day-closure
 * exists → save, last-owner check → demote, transfer clientId lookup → execute) across
 * concurrent requests and instances. The lock is held until the surrounding transaction
 * commits or rolls back; it must be called inside an active transaction.</p>
 *
 * <p>Keys must be globally unique: include the tenant schema or a globally unique id
 * (e.g. {@code "product-limit:" + tenantSchema}, {@code "day-closure:" + storeId}).
 * Take at most one lock per transaction to stay clear of lock-ordering deadlocks.</p>
 *
 * <p>Pure Java — NO Spring/JPA imports.</p>
 */
public interface TransactionLockPort {

    /**
     * Blocks until the transaction-scoped lock for {@code key} is held by the current
     * transaction. Released automatically at commit/rollback.
     *
     * @throws RuntimeException (IllegalTransactionStateException) when no transaction is active
     */
    void lock(String key);
}

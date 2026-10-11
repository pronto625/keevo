package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.application.port.TransactionLockPort;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgresTransactionLockAdapter — {@link TransactionLockPort} backed by PostgreSQL
 * transaction-scoped advisory locks (Story 13.9).
 *
 * <p>{@code pg_advisory_xact_lock(hashtextextended(key, 0))} blocks until the lock is free and
 * is released by PostgreSQL at commit/rollback (or connection loss). The query runs through the
 * transaction's own {@link EntityManager} so it uses the very connection of the caller's
 * transaction (a raw JdbcTemplate under the multi-tenant connection provider would not).
 * {@code Propagation.MANDATORY}: calling it outside a transaction is a programming error.</p>
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PostgresTransactionLockAdapter implements TransactionLockPort {

    private final EntityManager entityManager;

    public PostgresTransactionLockAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void lock(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("lock key must not be blank");
        }
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", key)
                .getSingleResult();
    }
}

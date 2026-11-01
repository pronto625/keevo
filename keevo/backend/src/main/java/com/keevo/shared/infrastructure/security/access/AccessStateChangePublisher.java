package com.keevo.shared.infrastructure.security.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Story 16.11 — announces an access-state change on every instance.
 *
 * <p>Changes to the public tables (membership, account status, refresh tokens) are broadcast by
 * database triggers (V36). Rows of the account schemas (employee store, status, password flag) have
 * no trigger: the services changing them call {@link #userChanged}. The broadcast is a PostgreSQL
 * {@code NOTIFY} on {@link #CHANNEL}, delivered on commit (never for a rolled-back change); the local
 * cache is also evicted immediately and again after commit.
 */
@Component
public class AccessStateChangePublisher {

    public static final String CHANNEL = "keevo_access_state";

    private final JdbcTemplate jdbcTemplate;
    private final AccessStateService accessStates;

    public AccessStateChangePublisher(JdbcTemplate jdbcTemplate, AccessStateService accessStates) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessStates = accessStates;
    }

    /** The user's role, store, status or password flag changed (any account). */
    public void userChanged(UUID userId) {
        publish("u:" + userId, () -> accessStates.invalidateUser(userId));
    }

    /** Something account-wide changed for {@code schema}. */
    public void tenantChanged(String schema) {
        publish("t:" + schema, () -> accessStates.invalidateTenant(schema));
    }

    private void publish(String payload, Runnable localEviction) {
        localEviction.run();
        jdbcTemplate.query("SELECT pg_notify(?, ?)", rs -> null, CHANNEL, payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    localEviction.run();
                }
            });
        }
    }
}

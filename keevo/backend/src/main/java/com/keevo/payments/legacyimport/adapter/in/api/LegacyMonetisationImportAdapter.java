package com.keevo.payments.legacyimport.adapter.in.api;

import com.keevo.payments.api.LegacyMonetisationImportPort;
import com.keevo.payments.credit.domain.model.CreditMovementKind;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import com.keevo.payments.legacyimport.adapter.out.persistence.JdbcLegacyMonetisationWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.5 — implements {@link LegacyMonetisationImportPort}: the inserts go through
 * {@link JdbcLegacyMonetisationWriter} (one statement per table), the credit through the 21.3 credit store (balance and
 * journal in one statement, kind {@code MIGRATION}). MANDATORY: the import's transaction commits the rows with their
 * migration records.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class LegacyMonetisationImportAdapter implements LegacyMonetisationImportPort {

    private final JdbcLegacyMonetisationWriter writer;
    private final AccountCreditStore credits;

    LegacyMonetisationImportAdapter(JdbcLegacyMonetisationWriter writer, AccountCreditStore credits) {
        this.writer = writer;
        this.credits = credits;
    }

    @Override
    public Set<UUID> importPaymentRequests(List<ImportedPaymentRequest> requests) {
        return requests == null || requests.isEmpty() ? Set.of() : writer.insertRequests(requests);
    }

    @Override
    public Set<UUID> importLedger(List<ImportedPayment> payments) {
        return payments == null || payments.isEmpty() ? Set.of() : writer.insertLedger(payments);
    }

    @Override
    public Set<UUID> importExternalSubscriptions(List<ImportedExternalSubscription> subscriptions) {
        return subscriptions == null || subscriptions.isEmpty() ? Set.of()
                : writer.insertExternalSubscriptions(subscriptions);
    }

    @Override
    public Set<String> importRevenueCatEvents(List<ImportedRevenueCatEvent> events) {
        return events == null || events.isEmpty() ? Set.of() : writer.insertEvents(events);
    }

    @Override
    public long creditAccount(UUID tenantId, long amountXaf, String reason) {
        if (amountXaf <= 0) {
            throw new IllegalArgumentException("credit must be positive");
        }
        return credits.credit(tenantId, amountXaf, CreditMovementKind.MIGRATION, null, null, reason);
    }
}

package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.event.DayClosedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * DayClosureWhatsAppListener — GoF Observer pattern, listens to DayClosedEvent.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Builds and sends WhatsApp report to the closing tenant's owner using ClosureReportStrategy.
 *
 * <p>v1s-14-16: the recipient is only ever the phone of the OWNER of {@code event.tenantId()}
 * (same rule as ReportHistoryService, v1s-15-7 D1). No owner / no phone → no send, warn only.
 * Runs AFTER_COMMIT of CloseDayService's transaction: no report for a rolled-back closure, and
 * nothing here (lookup, store read, send) can poison or roll back the closure. Every runtime
 * exception is caught and logged. TenantContext is set to {@code event.tenantId()} around the
 * store lookup (other AFTER_COMMIT listeners may have cleared it) and restored afterwards.
 *
 * <p>Note: with keevo.reporting.legacy-listener=true the owner receives this legacy report IN
 * ADDITION to the EndOfDayReportListener report (double send).
 *
 * <p>DEPRECATED by story 7.2 — replaced by EndOfDayReportListener.
 * Disabled by default; re-enable with keevo.reporting.legacy-listener=true.
 */
@ConditionalOnProperty(name = "keevo.reporting.legacy-listener", havingValue = "true", matchIfMissing = false)
@Component
public class DayClosureWhatsAppListener {

    private static final Logger log = LoggerFactory.getLogger(DayClosureWhatsAppListener.class);

    private final WhatsAppPort whatsAppPort;
    private final StoreQueryPort storeQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final ClosureReportStrategy manualReportStrategy;
    private final ClosureReportStrategy autoReportStrategy;

    public DayClosureWhatsAppListener(WhatsAppPort whatsAppPort,
                                      StoreQueryPort storeQuery,
                                      OwnerRecipientResolver ownerRecipientResolver) {
        this.whatsAppPort = whatsAppPort;
        this.storeQuery = storeQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.manualReportStrategy = new ManualReportStrategy();
        this.autoReportStrategy = new AutoReportStrategy();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDayClosed(DayClosedEvent event) {
        String previousTenant = TenantContext.getCurrentTenant();
        try {
            log.info("DayClosedEvent received: storeId={}, isAutomatic={}",
                    event.storeId(), event.isAutomatic());

            // v1s-14-16 / v1s-14-17: owner check before building the report — never a fallback recipient.
            // The resolver returns empty for a null/blank tenant, a missing owner or an unusable phone.
            Optional<String> ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId());
            if (ownerPhone.isEmpty()) {
                log.warn("Day-closure WhatsApp report skipped for tenant={} storeId={}: no owner WhatsApp phone",
                        event.tenantId(), event.storeId());
                return;
            }

            TenantContext.setCurrentTenant(event.tenantId());
            String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), event.storeId())
                    .map(s -> s.name())
                    .orElse("Boutique #" + event.storeId().toString().substring(0, 8));

            // MVP: employee name from phone (User.fullName not available in this story)
            String employeeName = "Employé";

            ClosureReportStrategy strategy = event.isAutomatic() ? autoReportStrategy : manualReportStrategy;
            String report = strategy.buildReport(event, storeName, employeeName);

            whatsAppPort.sendReport(ownerPhone.get(), report);
        } catch (RuntimeException e) {
            // Closure is already committed (AFTER_COMMIT): log and skip, never propagate.
            log.warn("Day-closure WhatsApp report failed for tenant={} storeId={}: {}: {}",
                    event.tenantId(), event.storeId(), e.getClass().getSimpleName(), e.getMessage(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.setCurrentTenant(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}

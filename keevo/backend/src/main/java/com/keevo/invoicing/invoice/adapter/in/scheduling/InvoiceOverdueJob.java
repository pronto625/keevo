package com.keevo.invoicing.invoice.adapter.in.scheduling;

import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.catalog.invoicing.InvoicesOverdueEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ModuleStateScope;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Story 20.4 — the daily overdue job of invoices (FUS-FR20), at 07:00 Douala time by default
 * ({@code keevo.invoice-overdue.cron}). Template Method over the live tenants ({@link PlanModuleRowStore#liveTenants()},
 * as {@code NotificationRetentionJob}): a tenant without the {@code invoice_payments} table (module never used, or not
 * migrated to V10 yet — it has no instalment; Story 20.5: or below V11, without invoice due dates) or whose
 * {@code invoicing} is not ENABLED (off, or read-only) is
 * skipped; per tenant, in its own transaction, ONE set-based UPDATE moves the active ISSUED invoices holding an unpaid
 * instalment due before today (Africa/Douala) — or, Story 20.5, past their own due date with less received than due
 * (total TTC − credit notes) — to {@code OVERDUE}; only those not already OVERDUE nor PAID, so a later run finds
 * nothing new — and,
 * when some moved, ONE {@link InvoicesOverdueEvent} is published (the owner notification is sent by the messaging side
 * after the commit). A tenant whose transaction fails is logged and counted, the others proceed. One summary line per
 * run.
 *
 * <p>Complexity: one catalogue query for the schemas holding the table, then 1 statement per tenant driven by the
 * partial index of unpaid instalments ({@code idx_invoice_payments_unpaid_due}). Runs only when scheduling is on
 * ({@code keevo.scheduling.enabled}); one instance per tick (ShedLock).
 */
@Component
public class InvoiceOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceOverdueJob.class);

    private final PlanModuleRowStore tenants;
    private final InvoicePaymentRepository payments;
    private final ModuleRegistry modules;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InvoiceOverdueJob(PlanModuleRowStore tenants, InvoicePaymentRepository payments, ModuleRegistry modules,
                             ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                             Clock clock) {
        this.tenants = tenants;
        this.payments = payments;
        this.modules = modules;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param tenants  tenants processed
     * @param skipped  live tenants without the payments table, with an invalid schema or with invoicing not enabled
     * @param overdue  invoices newly marked overdue
     * @param notified tenants for which a notification event was published
     * @param failures tenants whose transaction failed (rolled back, retried next run)
     */
    public record Report(int tenants, int skipped, int overdue, int notified, int failures) {
    }

    @Scheduled(cron = "${keevo.invoice-overdue.cron:0 0 7 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "invoiceOverdueJob.markOverdue", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Invoice overdue: daily run failed ({})", e.getClass().getSimpleName(), e);
        }
    }

    /** One run over every live tenant. */
    public Report run() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        int processed = 0;
        int skipped = 0;
        int overdue = 0;
        int notified = 0;
        int failures = 0;
        Set<String> withPayments = payments.schemasWithPayments();
        for (LiveTenant tenant : tenants.liveTenants()) {
            String schema = tenant.tenantSchema();
            if (!tenant.schemaPresent() || !TenantSchema.isValid(schema) || !withPayments.contains(schema)
                    || !enabled(schema)) {
                skipped++;
                continue;
            }
            try {
                TenantContext.setCurrentTenant(schema);
                List<String> numbers = transactions.execute(status -> {
                    List<String> marked = payments.markOverdue(today, now);
                    if (!marked.isEmpty()) {
                        events.publishEvent(new InvoicesOverdueEvent(marked, null, schema, now));
                    }
                    return marked;
                });
                processed++;
                if (numbers != null && !numbers.isEmpty()) {
                    overdue += numbers.size();
                    notified++;
                }
            } catch (RuntimeException e) {
                failures++;
                log.warn("Invoice overdue: tenant {} skipped ({}) — rows left unchanged", tenant.code(),
                        e.getClass().getSimpleName());
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Invoice overdue: {} tenant(s) processed, {} skipped, {} failed — {} invoice(s) newly overdue, "
                + "{} owner notification(s)", processed, skipped, failures, overdue, notified);
        return new Report(processed, skipped, overdue, notified, failures);
    }

    /**
     * Only a tenant whose invoicing is ENABLED: turned off by the owner, or READ_ONLY (lapsed plan) → no marking, no
     * notification. Unreadable state → skipped (retried next run).
     */
    private boolean enabled(String schema) {
        try (var scope = ModuleStateScope.open()) {
            return modules.moduleState(schema, InvoicingModule.ID) == ModuleState.ENABLED;
        } catch (RuntimeException e) {
            log.warn("Invoice overdue: module state unreadable for tenant {} ({}) — skipped", schema,
                    e.getClass().getSimpleName());
            return false;
        }
    }
}

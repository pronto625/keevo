package com.keevo.invoicing.invoice.adapter.in.scheduling;

import com.keevo.invoicing.invoice.application.service.InvoiceSendOutbox;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import com.keevo.invoicing.module.InvoicingModule;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Story 20.8 — the send outbox poller (Bref's {@code @Cron(EVERY_30_SECONDS)} {@code processPending}), every 30 s by
 * default ({@code keevo.invoice-send-outbox.delay}). Template Method over the live tenants, as
 * {@link InvoiceOverdueJob}: one catalogue query for the schemas holding {@code invoice_send_requests}; a tenant
 * without it, with an invalid schema or whose {@code invoicing} is not ENABLED is skipped (its requests wait); per
 * tenant, {@link InvoiceSendOutbox#pollTenant} (lease recovery + one set-based claim of the due rows). A failing
 * tenant is logged, the others proceed. One instance per tick (ShedLock); runs only when scheduling is on.
 */
@Component
public class InvoiceSendOutboxJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSendOutboxJob.class);

    private final PlanModuleRowStore tenants;
    private final InvoiceSendRequestRepository requests;
    private final InvoiceSendOutbox outbox;
    private final ModuleRegistry modules;

    public InvoiceSendOutboxJob(PlanModuleRowStore tenants, InvoiceSendRequestRepository requests,
                                InvoiceSendOutbox outbox, ModuleRegistry modules) {
        this.tenants = tenants;
        this.requests = requests;
        this.outbox = outbox;
        this.modules = modules;
    }

    /** @param processed requests claimed and processed; @param failures tenants whose tick failed */
    public record Report(int tenants, int processed, int failures) {
    }

    @Scheduled(fixedDelayString = "${keevo.invoice-send-outbox.delay:PT30S}",
            initialDelayString = "${keevo.invoice-send-outbox.initial-delay:PT60S}")
    @SchedulerLock(name = "invoiceSendOutboxJob.poll", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Invoice send outbox: run failed ({})", e.getClass().getSimpleName(), e);
        }
    }

    /** One tick over every live tenant holding the outbox. */
    public Report run() {
        Set<String> withOutbox = requests.schemasWithOutbox();
        int processedTenants = 0;
        int processed = 0;
        int failures = 0;
        if (withOutbox.isEmpty()) {
            return new Report(0, 0, 0);
        }
        for (LiveTenant tenant : tenants.liveTenants()) {
            String schema = tenant.tenantSchema();
            if (!tenant.schemaPresent() || !TenantSchema.isValid(schema) || !withOutbox.contains(schema)
                    || !enabled(schema)) {
                continue;
            }
            try {
                TenantContext.setCurrentTenant(schema);
                processed += outbox.pollTenant(schema);
                processedTenants++;
            } catch (RuntimeException e) {
                failures++;
                log.warn("Invoice send outbox: tenant {} skipped ({})", tenant.code(), e.getClass().getSimpleName());
            } finally {
                TenantContext.clear();
            }
        }
        if (processed > 0 || failures > 0) {
            log.info("Invoice send outbox: {} tenant(s), {} request(s) processed, {} failed tenant(s)",
                    processedTenants, processed, failures);
        }
        return new Report(processedTenants, processed, failures);
    }

    private boolean enabled(String schema) {
        try (var scope = ModuleStateScope.open()) {
            return modules.moduleState(schema, InvoicingModule.ID) == ModuleState.ENABLED;
        } catch (RuntimeException e) {
            log.warn("Invoice send outbox: module state unreadable for tenant {} ({}) — skipped", schema,
                    e.getClass().getSimpleName());
            return false;
        }
    }
}

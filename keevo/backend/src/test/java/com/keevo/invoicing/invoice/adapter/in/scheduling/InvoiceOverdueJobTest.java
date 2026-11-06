package com.keevo.invoicing.invoice.adapter.in.scheduling;

import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.shared.event.catalog.invoicing.InvoicesOverdueEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceOverdueJob — daily overdue marking, one event per tenant (Story 20.4)")
class InvoiceOverdueJobTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

    private final PlanModuleRowStore tenants = mock(PlanModuleRowStore.class);
    private final InvoicePaymentRepository payments = mock(InvoicePaymentRepository.class);
    private final ModuleRegistry modules = mock(ModuleRegistry.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final InvoiceOverdueJob job = new InvoiceOverdueJob(tenants, payments, modules, events,
            mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private static LiveTenant tenant(String schema) {
        return new LiveTenant(UUID.randomUUID(), schema.toUpperCase(), schema, true);
    }

    @Test
    @DisplayName("per tenant bound: newly overdue → one event with the numbers; none → no event; disabled / read-only / "
            + "without table skipped; a failing tenant does not stop the others")
    void run() {
        when(tenants.liveTenants()).thenReturn(List.of(tenant("kv_aaaaaa"), tenant("kv_bbbbbb"), tenant("kv_cccccc"),
                tenant("kv_dddddd"), tenant("kv_eeeeee"), tenant("kv_ffffff")));
        when(payments.schemasWithPayments()).thenReturn(Set.of("kv_aaaaaa", "kv_bbbbbb", "kv_cccccc", "kv_eeeeee", "kv_ffffff"));
        when(modules.moduleState(any(), eq("invoicing"))).thenReturn(ModuleState.ENABLED);
        when(modules.moduleState("kv_cccccc", "invoicing")).thenReturn(ModuleState.DISABLED);
        when(modules.moduleState("kv_ffffff", "invoicing")).thenReturn(ModuleState.READ_ONLY); // lapsed plan
        AtomicReference<String> bound = new AtomicReference<>();
        when(payments.markOverdue(LocalDate.parse("2026-11-06"), NOW)).thenAnswer(a -> {
            bound.set(TenantContext.getCurrentTenant());
            return switch (bound.get()) {
                case "kv_aaaaaa" -> List.of("FAC-2026-000012", "FAC-2026-000015");
                case "kv_eeeeee" -> throw new IllegalStateException("boom");
                default -> List.of();
            };
        });

        InvoiceOverdueJob.Report report = job.run();

        assertThat(report).isEqualTo(new InvoiceOverdueJob.Report(2, 3, 2, 1, 1));
        ArgumentCaptor<InvoicesOverdueEvent> event = ArgumentCaptor.forClass(InvoicesOverdueEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo("kv_aaaaaa");
        assertThat(event.getValue().numbers()).containsExactly("FAC-2026-000012", "FAC-2026-000015");
        assertThat(event.getValue().actorId()).isNull();
        verify(payments, times(3)).markOverdue(any(), any());
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("no tenant with the payments table: nothing marked")
    void nothing() {
        when(tenants.liveTenants()).thenReturn(List.of(tenant("kv_aaaaaa")));
        when(payments.schemasWithPayments()).thenReturn(Set.of());
        assertThat(job.run().skipped()).isEqualTo(1);
        verify(payments, never()).markOverdue(any(), any());
    }
}

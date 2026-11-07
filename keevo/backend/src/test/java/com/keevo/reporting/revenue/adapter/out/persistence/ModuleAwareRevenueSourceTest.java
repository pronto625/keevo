package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 20.9 — the revenue source's branches follow the tenant's modules (module autonomy). */
class ModuleAwareRevenueSourceTest {

    private static final String TENANT = "kv_abc123";

    private final ModuleRegistry registry = mock(ModuleRegistry.class);
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ModuleAwareRevenueSource source(ModuleRegistry r, ModuleProvisioner p) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (r != null) beans.addBean("registry", r);
        if (p != null) beans.addBean("provisioner", p);
        ObjectProvider<ModuleRegistry> rp = beans.getBeanProvider(ModuleRegistry.class);
        ObjectProvider<ModuleProvisioner> pp = beans.getBeanProvider(ModuleProvisioner.class);
        return new ModuleAwareRevenueSource(rp, pp);
    }

    private void states(ModuleState pos, ModuleState invoicing) {
        TenantContext.setCurrentTenant(TENANT);
        when(registry.moduleStates(TENANT)).thenReturn(Map.of("pos", pos, "invoicing", invoicing));
    }

    @Test
    @DisplayName("pos and invoicing active: sales, invoices and credit notes; invoicing tables provisioned first")
    void both() {
        states(ModuleState.ENABLED, ModuleState.READ_ONLY);
        String docs = source(registry, provisioner).documents();
        assertThat(docs).contains("FROM sales s").contains("FROM invoices i").contains("FROM credit_notes cn");
        verify(provisioner).ensureProvisioned(TENANT, "invoicing");
    }

    @Test
    @DisplayName("invoicing disabled: sales only, nothing provisioned (V1 Keevo tenant)")
    void posOnly() {
        states(ModuleState.ENABLED, ModuleState.DISABLED);
        assertThat(source(registry, provisioner).documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        assertThat(source(registry, provisioner).lines()).isEqualTo(RevenueRelations.POS_LINES);
        verify(provisioner, never()).ensureProvisioned(anyString(), anyString());
    }

    @Test
    @DisplayName("pos disabled: documents only")
    void documentsOnly() {
        states(ModuleState.DISABLED, ModuleState.ENABLED);
        String lines = source(registry, provisioner).lines();
        assertThat(lines).doesNotContain("FROM sale_items").contains("FROM invoice_items it")
                .contains("FROM credit_note_items cni");
    }

    @Test
    @DisplayName("neither module: an empty relation")
    void none() {
        states(ModuleState.DISABLED, ModuleState.DISABLED);
        assertThat(source(registry, provisioner).documents()).contains("WHERE FALSE").doesNotContain("FROM sales");
    }

    @Test
    @DisplayName("no tenant, invalid schema or no registry: sales only")
    void defaults() {
        assertThat(source(registry, provisioner).documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        TenantContext.setCurrentTenant("public");
        assertThat(source(registry, provisioner).documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        TenantContext.setCurrentTenant(TENANT);
        assertThat(source(null, null).documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
    }

    @Test
    @DisplayName("unreadable module states: sales only; a provisioning failure propagates (no partial figure)")
    void failures() {
        TenantContext.setCurrentTenant(TENANT);
        when(registry.moduleStates(TENANT)).thenThrow(new IllegalStateException("db down"));
        assertThat(source(registry, provisioner).documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);

        ModuleRegistry ok = mock(ModuleRegistry.class);
        when(ok.moduleStates(TENANT)).thenReturn(Map.of("pos", ModuleState.ENABLED, "invoicing", ModuleState.ENABLED));
        doThrow(new IllegalStateException("migration failed")).when(provisioner).ensureProvisioned(TENANT, "invoicing");
        assertThatThrownBy(() -> source(ok, provisioner).documents()).hasMessage("migration failed");
    }

    @Test
    @DisplayName("every branch filters the counted documents the same way (one definition)")
    void countedInvoicePredicate() {
        String docs = RevenueRelations.documents(true, true);
        assertThat(docs.split("i.source_sale_id IS NULL", -1)).hasSize(3); // invoices + credit notes
        assertThat(docs).contains("s.status = 'COMPLETED'").contains("i.kind = 'INVOICE'")
                .contains("i.status IN ('ISSUED', 'PAID')").contains("i.deleted_at IS NULL")
                .contains("i.superseded_at IS NULL").contains("-cn.total_ttc");
    }
}

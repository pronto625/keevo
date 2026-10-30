package com.keevo.crm.module;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleTenantContext;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Story 13.9 — crm converted to {@link KeevoModule}, in the real application context on PostgreSQL: manifest +
 * module bean, {@code ClientLookupPort} provided through {@code registerCapabilities} only, a new tenant gets the crm
 * tables (core DDL) adopted by {@code flyway_history_crm} at version 1 (baseline), and the per-tenant lifecycle calls the hooks once and gates the
 * capability. The module bean is a spy to count the hook calls.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("crm KeevoModule — full context (Story 13.9)")
class CrmModuleFullContextTest extends FullContextIntegrationTest {

    @MockitoSpyBean CrmModule crmModule;
    @Autowired KeevoModuleDirectory modules;
    @Autowired ModuleRegistry registry;
    @Autowired CapabilityRegistry capabilities;
    @Autowired ModuleLifecycle lifecycle;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    private final ModuleLifecycle.Actor admin = new ModuleLifecycle.Actor(UUID.randomUUID(), true);
    private String schema;
    private UUID tenantId;
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone) VALUES (?, 'Awa Ndiaye', "
                + "'+237690000002')", clientId);
        TenantContext.setCurrentTenant(schema);
        clearInvocations(crmModule);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        registry.evict(schema);
    }

    @Test
    @DisplayName("crm has a manifest and a KeevoModule bean; ClientLookupPort is no bean (registry only)")
    void convertedModule() {
        assertThat(registry.definition("crm")).hasValueSatisfying(m -> assertThat(m.provides())
                .containsExactly("ClientLookupPort"));
        assertThat(modules.module("crm")).hasValueSatisfying(m -> assertThat(m.id()).isEqualTo("crm"));
        assertThat(modules.module("reporting")).isPresent();
        assertThat(context.getBeansOfType(ClientLookupPort.class)).isEmpty();
    }

    @Test
    @DisplayName("new tenant: crm tables (core DDL) adopted — flyway_history_crm baselined at 1, row ACTIVE/FREE v1")
    void newTenantProvisioned() {
        assertThat(jdbc.queryForList("SELECT COALESCE(version, '-') || ':' || type || ':' || success FROM \"" + schema
                + "\".flyway_history_crm ORDER BY installed_rank", String.class)).containsExactly("1:BASELINE:true");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, tier, provisioned_version FROM "
                + "public.tenant_modules WHERE tenant_id = ? AND module_id = 'crm'", tenantId);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("tier", "FREE")
                .containsEntry("provisioned_version", "1");
    }

    @Test
    @DisplayName("crm ENABLED → capability resolves; deactivate → onDeactivate once, find empty; reactivate → "
            + "onActivate once, provisioning no-op")
    void lifecycleGatesTheCapability() {
        assertThat(capabilities.find(ClientLookupPort.class, schema))
                .hasValueSatisfying(port -> assertThat(port.displayName(schema, clientId)).contains("Awa Ndiaye"));

        lifecycle.deactivate(tenantId, "crm", admin);
        ArgumentCaptor<ModuleTenantContext> ctx = ArgumentCaptor.forClass(ModuleTenantContext.class);
        verify(crmModule, times(1)).onDeactivate(ctx.capture());
        assertThat(ctx.getValue().tenantSchema()).isEqualTo(schema);
        assertThat(ctx.getValue().tenantCode()).startsWith("KV-");
        assertThat(ctx.getValue().actor().superAdmin()).isTrue();
        verify(crmModule, never()).onActivate(any());
        registry.evict(schema);
        assertThat(capabilities.find(ClientLookupPort.class, schema)).isEmpty();

        lifecycle.deactivate(tenantId, "crm", admin); // already disabled
        verify(crmModule, times(1)).onDeactivate(any());

        int historyRows = jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".flyway_history_crm",
                Integer.class);
        lifecycle.activate(tenantId, "crm", null, admin);
        verify(crmModule, times(1)).onActivate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".flyway_history_crm",
                Integer.class)).isEqualTo(historyRows);
        registry.evict(schema);
        assertThat(capabilities.find(ClientLookupPort.class, schema)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients", Integer.class))
                .isEqualTo(1); // data kept across the cycle
    }
}

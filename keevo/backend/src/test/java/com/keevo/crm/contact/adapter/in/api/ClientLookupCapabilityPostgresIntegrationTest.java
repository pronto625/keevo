package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.6 — {@code ClientLookupPort} through the real {@link CapabilityRegistry} (production beans, manifests and
 * {@code public.tenant_modules} on PostgreSQL): crm usable → the crm implementation resolves a real client's name;
 * crm DISABLED for the tenant → empty.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ClientLookupPort capability — full context (Story 13.6)")
class ClientLookupCapabilityPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired CapabilityRegistry capabilities;
    @Autowired ModuleRegistry modules;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final AtomicInteger transactionsBegun = new AtomicInteger();
    private final TransactionExecutionListener counter = new TransactionExecutionListener() {
        @Override
        public void beforeBegin(TransactionExecution transaction) {
            transactionsBegun.incrementAndGet();
        }
    };

    private String schema;
    private UUID tenantId;
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone) VALUES (?, 'Awa Ndiaye', '+237690000002')",
                clientId);
        TenantContext.setCurrentTenant(schema);
        ((AbstractPlatformTransactionManager) transactionManager).addListener(counter);
    }

    @AfterEach
    void tearDown() {
        ((AbstractPlatformTransactionManager) transactionManager).getTransactionExecutionListeners().remove(counter);
        TenantContext.clear();
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        modules.evict(schema);
    }

    private void crmRow(String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'crm', ?, 'FREE')",
                tenantId, status);
    }

    @Test
    @DisplayName("same tenant: crm ACTIVE row → the crm implementation resolves the client; then DISABLED → empty")
    void activeThenDisabled() {
        crmRow("ACTIVE");
        Optional<ClientLookupPort> lookup = capabilities.find(ClientLookupPort.class, schema);
        assertThat(transactionsBegun).as("find opens no transaction").hasValue(0);
        assertThat(lookup).isPresent();
        assertThat(lookup.get().displayName(schema, clientId)).contains("Awa Ndiaye");
        assertThat(lookup.get().exists(schema, clientId)).isTrue();
        assertThat(lookup.get().displayName(schema, UUID.randomUUID())).isEmpty();

        jdbc.update("UPDATE public.tenant_modules SET status = 'DISABLED' WHERE tenant_id = ? AND module_id = 'crm'",
                tenantId);
        modules.evict(schema);
        assertThat(capabilities.find(ClientLookupPort.class, schema)).isEmpty();
        assertThat(capabilities.has(ClientLookupPort.class, schema)).isFalse();
    }

    @Test
    @DisplayName("crm free without a row → found")
    void freeWithoutRow() {
        assertThat(capabilities.has(ClientLookupPort.class, schema)).isTrue();
    }

    @Test
    @DisplayName("unknown tenant and platform schema → empty")
    void unknownTenantAndPlatform() {
        assertThat(capabilities.find(ClientLookupPort.class, "kv_zz9999")).isEmpty();
        assertThat(capabilities.find(ClientLookupPort.class, "public")).isEmpty();
    }
}

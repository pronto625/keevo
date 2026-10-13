package com.keevo.admin.tenant.application.service;

import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 11.5 — the tenant list resolves the schema names of the page with one batched query
 * against a real {@code public.tenants}, then reads each tenant's own schema for its metrics.
 *
 * <p>Commits tenant rows and schemas, removed in {@link #cleanUp()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(TenantSchemaProvisioner.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Admin tenant list on PostgreSQL (Story 11.5)")
class AdminTenantListPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tenantIds.forEach(id -> jdbc.update("DELETE FROM public.tenants WHERE id = ?", id));
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
    }

    @Test
    @DisplayName("two tenants, one active store each → storeCount == 1 for both")
    void listResolvesEachTenantSchema() {
        String tag = "Boutique KSA " + UUID.randomUUID().toString().substring(0, 8);
        UUID a = tenant("kv_ksa001", "KV-KSA001", tag + " A");
        UUID b = tenant("kv_ksa002", "KV-KSA002", tag + " B");

        AdminTenantService service = new AdminTenantService(jdbc, mock(AccountDeletionScheduler.class));
        Page<AdminTenantListItem> page = service.execute(
                new ListTenantsQuery(tag, null, null, null, null, null, null, 0, 25));

        assertThat(page.getContent()).extracting(AdminTenantListItem::id).containsExactlyInAnyOrder(a, b);
        assertThat(page.getContent()).extracting(AdminTenantListItem::storeCount).containsOnly(1);
    }

    private UUID tenant(String schema, String code, String name) {
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema); // seeds one active store
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, ?, 1, 1, 1, 'FREE', ?, 'ACTIVE')", id, code, name, schema);
        return id;
    }
}

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
@Import({TenantSchemaProvisioner.class, AdminTenantService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Admin tenant list on PostgreSQL (Story 11.5)")
class AdminTenantListPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;
    /** The Spring bean (with its proxies), so a transaction re-added on the read path would show. */
    @Autowired AdminTenantService springService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AccountDeletionScheduler accountDeletionScheduler;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userIds.forEach(u -> {
            jdbc.update("DELETE FROM public.user_daily_activity WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        });
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

    @Test
    @DisplayName("9-1 — real SQL: owner name in the list, employee names + last activity, readable audit line")
    void listAndDetailAreReadable() {
        String tag = "Boutique KSB " + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = tenant("kv_ksb001", "KV-KSB001", tag);
        UUID owner = member(tenantId, "OWNER");
        UUID employee = member(tenantId, "EMPLOYEE");
        UUID storeId = jdbc.queryForObject("SELECT id FROM kv_ksb001.stores LIMIT 1", UUID.class);
        jdbc.update("UPDATE kv_ksb001.stores SET name = 'Boutique Centre' WHERE id = ?", storeId);
        jdbc.update("INSERT INTO kv_ksb001.employees (user_id, store_id, first_name, last_name) VALUES (?, ?, ' Awa ', 'Diallo')",
                owner, storeId);
        jdbc.update("INSERT INTO kv_ksb001.employees (user_id, store_id, first_name, last_name) VALUES (?, ?, 'Moussa', 'Kane')",
                employee, storeId);
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, CURRENT_DATE - 1)",
                employee, tenantId);
        jdbc.update("INSERT INTO kv_ksb001.audit_log (id, action, entity_id, entity_type, occurred_at, user_id, "
                + "store_id, entity_label, source) VALUES (?, 'PRODUCT_UPDATED', ?, 'Product', now(), ?, ?, 'Savon', 'ONLINE')",
                UUID.randomUUID(), UUID.randomUUID(), employee, storeId);
        jdbc.update("INSERT INTO kv_ksb001.audit_log (id, action, entity_id, entity_type, occurred_at, user_id, source) "
                + "VALUES (?, 'STOCK_THRESHOLD_BREACHED', ?, 'StockAlert', now() - interval '1 hour', ?, 'SYSTEM')",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        AdminTenantService service = new AdminTenantService(jdbc, mock(AccountDeletionScheduler.class));
        Page<AdminTenantListItem> page = service.execute(
                new ListTenantsQuery(tag, "FREE", null, null, null, null, null, 0, 25));
        assertThat(page.getContent()).singleElement().satisfies(it -> {
            assertThat(it.ownerName()).isEqualTo("Awa Diallo");
            assertThat(it.storeCount()).isEqualTo(1);
            assertThat(it.lastActivityAt()).isNotNull();
        });

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(tenantId, 0));
        assertThat(detail.employees()).extracting(e -> e.name() + "/" + e.role())
                .containsExactly("Awa Diallo/OWNER", "Moussa Kane/EMPLOYEE");
        assertThat(detail.employees().get(0).lastActivityAt()).isNull();
        assertThat(detail.employees().get(1).lastActivityAt()).isEqualTo(java.time.LocalDate.now().minusDays(1));
        assertThat(detail.auditEvents()).hasSize(2);
        var update = detail.auditEvents().get(0);
        assertThat(update.action()).isEqualTo("PRODUCT_UPDATED");
        assertThat(update.entityLabel()).isEqualTo("Savon");
        assertThat(update.actorName()).isEqualTo("Moussa Kane");
        assertThat(update.storeName()).isEqualTo("Boutique Centre");
        assertThat(detail.auditEvents().get(1).actorName()).isEqualTo("Système");
    }

    @Test
    @DisplayName("9-1 — a tenant with a broken schema (employees dropped) does not blank the next tenant's data")
    void brokenTenantDoesNotBlankTheNextOne() {
        String tag = "Boutique KSC " + UUID.randomUUID().toString().substring(0, 8);
        UUID healthy = tenant("kv_ksc001", "KV-KSC001", tag + " saine");
        UUID broken = tenant("kv_ksc002", "KV-KSC002", tag + " cassée");
        // the broken one is listed first (newest first)
        jdbc.update("UPDATE public.tenants SET created_at = now() + interval '1 minute' WHERE id = ?", broken);
        UUID owner = member(healthy, "OWNER");
        UUID storeId = jdbc.queryForObject("SELECT id FROM kv_ksc001.stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO kv_ksc001.employees (user_id, store_id, first_name, last_name) VALUES (?, ?, 'Awa', 'Diallo')",
                owner, storeId);
        member(broken, "OWNER");
        jdbc.execute("DROP TABLE kv_ksc002.employees");

        Page<AdminTenantListItem> page = springService.execute(
                new ListTenantsQuery(tag, null, null, null, null, null, null, 0, 25));

        assertThat(page.getContent()).extracting(AdminTenantListItem::id).containsExactly(broken, healthy);
        AdminTenantListItem brokenRow = page.getContent().get(0);
        assertThat(brokenRow.storeCount()).isEqualTo(1);            // metrics kept despite the broken table
        assertThat(brokenRow.ownerName()).isEqualTo("Propriétaire");
        AdminTenantListItem healthyRow = page.getContent().get(1);
        assertThat(healthyRow.storeCount()).isEqualTo(1);
        assertThat(healthyRow.ownerName()).isEqualTo("Awa Diallo");

        var detail = springService.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(broken, 0));
        assertThat(detail.employees()).extracting(e -> e.name()).containsExactly("Propriétaire");
    }

    @Test
    @DisplayName("9-1 — last-activity filter keeps only tenants whose latest audit entry is in range")
    void lastActivityFilter() {
        String tag = "Boutique KSD " + UUID.randomUUID().toString().substring(0, 8);
        UUID recent = tenant("kv_ksd001", "KV-KSD001", tag + " récente");
        UUID old = tenant("kv_ksd002", "KV-KSD002", tag + " ancienne");
        tenant("kv_ksd003", "KV-KSD003", tag + " jamais");
        jdbc.update("INSERT INTO kv_ksd001.audit_log (id, action, entity_id, entity_type, occurred_at, user_id) "
                + "VALUES (?, 'PRODUCT_CREATED', ?, 'Product', now() - interval '2 days', ?)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO kv_ksd002.audit_log (id, action, entity_id, entity_type, occurred_at, user_id) "
                + "VALUES (?, 'PRODUCT_CREATED', ?, 'Product', now() - interval '40 days', ?)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        Page<AdminTenantListItem> page = springService.execute(new ListTenantsQuery(tag, null, null, null, null,
                java.time.Instant.now().minus(java.time.Duration.ofDays(7)), java.time.Instant.now(), 0, 25));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(AdminTenantListItem::id).containsExactly(recent);
        assertThat(old).isNotNull();
    }

    private UUID member(UUID tenantId, String role) {
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        String phone = "+2439" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)",
                userId, phone, role);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, ?, ?, ?)",
                UUID.randomUUID(), role, tenantId, userId);
        return userId;
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

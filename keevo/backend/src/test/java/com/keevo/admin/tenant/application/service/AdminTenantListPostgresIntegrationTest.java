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
    private final java.util.Map<UUID, String> schemaOf = new java.util.HashMap<>();

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
    @DisplayName("two tenants with different store counts → each row reads its own schema")
    void listResolvesEachTenantSchema() {
        String tag = "Boutique KSA " + UUID.randomUUID().toString().substring(0, 8);
        UUID a = tenant(tag + " A");
        UUID b = tenant(tag + " B");
        // a second active store on B only: a mixed-up schema lookup would show
        jdbc.update("INSERT INTO \"" + schemaOf.get(b) + "\".stores (name, type, is_active) VALUES ('Boutique 2', 'STORE', true)");

        AdminTenantService service = new AdminTenantService(jdbc, mock(AccountDeletionScheduler.class));
        Page<AdminTenantListItem> page = service.execute(
                new ListTenantsQuery(tag, null, null, null, null, null, null, 0, 25));

        assertThat(page.getContent()).extracting(AdminTenantListItem::id).containsExactlyInAnyOrder(a, b);
        java.util.Map<UUID, Integer> storeCounts = page.getContent().stream()
                .collect(java.util.stream.Collectors.toMap(AdminTenantListItem::id, it -> (int) it.storeCount()));
        assertThat(storeCounts).containsEntry(a, 1).containsEntry(b, 2);
    }

    @Test
    @DisplayName("9-1 — real SQL: owner name in the list, employee names + last activity, readable audit line")
    void listAndDetailAreReadable() {
        String tag = "Boutique KSB " + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = tenant(tag);
        String sb = schemaOf.get(tenantId);
        UUID owner = member(tenantId, "OWNER");
        UUID employee = member(tenantId, "EMPLOYEE");
        UUID storeId = jdbc.queryForObject("SELECT id FROM " + sb + ".stores LIMIT 1", UUID.class);
        jdbc.update("UPDATE " + sb + ".stores SET name = 'Boutique Centre' WHERE id = ?", storeId);
        jdbc.update("INSERT INTO " + sb + ".employees (user_id, store_id, first_name, last_name) VALUES (?, ?, ' Awa ', 'Diallo')",
                owner, storeId);
        jdbc.update("INSERT INTO " + sb + ".employees (user_id, store_id, first_name, last_name) VALUES (?, ?, 'Moussa', 'Kane')",
                employee, storeId);
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, CURRENT_DATE - 1)",
                employee, tenantId);
        jdbc.update("INSERT INTO " + sb + ".audit_log (id, action, entity_id, entity_type, occurred_at, user_id, "
                + "store_id, entity_label, source) VALUES (?, 'PRODUCT_UPDATED', ?, 'Product', now(), ?, ?, 'Savon', 'ONLINE')",
                UUID.randomUUID(), UUID.randomUUID(), employee, storeId);
        jdbc.update("INSERT INTO " + sb + ".audit_log (id, action, entity_id, entity_type, occurred_at, user_id, source) "
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
        UUID healthy = tenant(tag + " saine");
        UUID broken = tenant(tag + " cassée");
        String healthySchema = schemaOf.get(healthy);
        // the broken one is listed first (newest first)
        jdbc.update("UPDATE public.tenants SET created_at = now() + interval '1 minute' WHERE id = ?", broken);
        UUID owner = member(healthy, "OWNER");
        UUID storeId = jdbc.queryForObject("SELECT id FROM " + healthySchema + ".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO " + healthySchema + ".employees (user_id, store_id, first_name, last_name) VALUES (?, ?, 'Awa', 'Diallo')",
                owner, storeId);
        member(broken, "OWNER");
        jdbc.execute("DROP TABLE " + schemaOf.get(broken) + ".employees");

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
    @DisplayName("last-activity filter keeps only tenants whose latest merchant event is in range")
    void lastActivityFilter() {
        String tag = "Boutique KSD " + UUID.randomUUID().toString().substring(0, 8);
        UUID recent = tenant(tag + " récente");
        UUID old = tenant(tag + " ancienne");
        UUID never = tenant(tag + " jamais");
        audit(recent, member(recent, "OWNER"), "now() - interval '2 days'");
        audit(old, member(old, "EMPLOYEE"), "now() - interval '40 days'");
        // Nightly automatic day closure on every tenant: technical, must not make them « active ».
        for (UUID id : List.of(recent, old, never)) systemDayClosure(id, "now() - interval '1 hour'");

        Page<AdminTenantListItem> page = springService.execute(new ListTenantsQuery(tag, null, null, null, null,
                java.time.Instant.now().minus(java.time.Duration.ofDays(7)), java.time.Instant.now(), 0, 25));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(AdminTenantListItem::id).containsExactly(recent);
    }

    @Test
    @DisplayName("last activity = latest sale / own user's audit / sync push / app use, never SYSTEM or outsiders")
    void lastActivityIgnoresTechnicalWrites() {
        String tag = "Boutique KSE " + UUID.randomUUID().toString().substring(0, 8);
        UUID bySale = tenant(tag + " vente");
        UUID byAudit = tenant(tag + " journal");
        UUID bySync = tenant(tag + " sync");
        UUID byLogin = tenant(tag + " connexion");
        UUID none = tenant(tag + " aucune");
        for (UUID id : List.of(bySale, byAudit, bySync, byLogin, none)) {
            systemDayClosure(id, "now() - interval '1 hour'");
            // a write by a user who is not a member of this tenant (e.g. Super Admin) is not merchant activity
            audit(id, UUID.randomUUID(), "now() - interval '1 hour'");
        }
        UUID storeId = jdbc.queryForObject("SELECT id FROM " + schemaOf.get(bySale) + ".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO " + schemaOf.get(bySale) + ".sales (store_id, employee_id, total_amount, status, "
                + "occurred_at, created_at) VALUES (?, ?, 1000, 'COMPLETED', now() - interval '3 days', now() - interval '3 days')",
                storeId, UUID.randomUUID());
        audit(byAudit, member(byAudit, "OWNER"), "now() - interval '4 days'");
        jdbc.update("INSERT INTO " + schemaOf.get(bySync) + ".sync_operations_log (id, operation_type, status, processed_at) "
                + "VALUES (?, 'CREATE_SALE', 'SUCCESS', now() - interval '5 days')", UUID.randomUUID().toString());
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date, first_seen_at) "
                + "VALUES (?, ?, CURRENT_DATE - 6, now() - interval '6 days')", member(byLogin, "EMPLOYEE"), byLogin);

        Page<AdminTenantListItem> page = springService.execute(
                new ListTenantsQuery(tag, null, null, null, null, null, null, 0, 25));

        java.util.Map<UUID, java.time.Instant> last = new java.util.HashMap<>();
        page.getContent().forEach(it -> last.put(it.id(), it.lastActivityAt()));
        java.time.Instant now = java.time.Instant.now();
        assertThat(last).containsOnlyKeys(bySale, byAudit, bySync, byLogin, none);
        assertThat(daysAgo(last.get(bySale), now)).isEqualTo(3);
        assertThat(daysAgo(last.get(byAudit), now)).isEqualTo(4);
        assertThat(daysAgo(last.get(bySync), now)).isEqualTo(5);
        assertThat(daysAgo(last.get(byLogin), now)).isEqualTo(6);
        assertThat(last.get(none)).isNull(); // shown « — »
    }

    @Test
    @DisplayName("a sale dated in the future (wrong phone clock) never pushes last activity past now")
    void lastActivityCapsFutureSaleAtNow() {
        String tag = "Boutique KSF " + UUID.randomUUID().toString().substring(0, 8);
        UUID future = tenant(tag + " futur");
        UUID storeId = jdbc.queryForObject("SELECT id FROM " + schemaOf.get(future) + ".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO " + schemaOf.get(future) + ".sales (store_id, employee_id, total_amount, status, "
                + "occurred_at, created_at) VALUES (?, ?, 1000, 'COMPLETED', now() + interval '10 days', now())",
                storeId, UUID.randomUUID());

        java.time.Instant before = java.time.Instant.now().plusSeconds(5);
        Page<AdminTenantListItem> page = springService.execute(
                new ListTenantsQuery(tag, null, null, null, null, null, null, 0, 25));

        assertThat(page.getContent()).singleElement()
                .satisfies(it -> assertThat(it.lastActivityAt()).isNotNull().isBefore(before));
    }

    private static long daysAgo(java.time.Instant at, java.time.Instant now) {
        assertThat(at).isNotNull();
        return java.time.Duration.between(at, now).toHours() / 24;
    }

    private void audit(UUID tenantId, UUID userId, String occurredAtSql) {
        jdbc.update("INSERT INTO " + schemaOf.get(tenantId) + ".audit_log (id, action, entity_id, entity_type, occurred_at, user_id) "
                + "VALUES (?, 'PRODUCT_CREATED', ?, 'Product', " + occurredAtSql + ", ?)",
                UUID.randomUUID(), UUID.randomUUID(), userId);
    }

    /** The audit line the nightly automatic day closure writes for every active store. */
    private void systemDayClosure(UUID tenantId, String occurredAtSql) {
        jdbc.update("INSERT INTO " + schemaOf.get(tenantId) + ".audit_log (id, action, entity_id, entity_type, occurred_at, user_id, source) "
                + "VALUES (?, 'DAY_CLOSED', ?, 'DayClosure', " + occurredAtSql + ", '00000000-0000-0000-0000-000000000000'::uuid, 'SYSTEM')",
                UUID.randomUUID(), UUID.randomUUID());
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

    /** Random valid tenant schema name, so reruns and parallel suites never collide. */
    private static String randomSchema() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder("kv_");
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private UUID tenant(String name) {
        String schema = randomSchema();
        String code = "KV-" + schema.substring(3).toUpperCase();
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema); // seeds one active store
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, ?, 1, 1, 1, 'FREE', ?, 'ACTIVE')", id, code, name, schema);
        schemaOf.put(id, schema);
        return id;
    }
}

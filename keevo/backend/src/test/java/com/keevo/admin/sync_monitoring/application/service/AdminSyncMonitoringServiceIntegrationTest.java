package com.keevo.admin.sync_monitoring.application.service;

import com.keevo.admin.sync_monitoring.domain.model.AdminDeviceInfo;
import com.keevo.admin.sync_monitoring.domain.model.AdminSyncTenantDetail;
import com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9-8 — the device label lookups of {@link AdminSyncMonitoringService} on a real PostgreSQL
 * (Flyway-migrated public + one provisioned tenant schema).
 *
 * <p>Runs outside the test transaction ({@code NOT_SUPPORTED}) so each service statement
 * autocommits, as in production (the service is not transactional).
 * Data is committed with unique ids / schema and removed after each test.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(AdminSyncMonitoringService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AdminSyncMonitoringService on PostgreSQL (9-8 device labels)")
class AdminSyncMonitoringServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AdminSyncMonitoringService service;

    private UUID tenantId;
    private String schema;
    private final UUID employee = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID unknown = UUID.randomUUID();
    private final String empPhone = UUID.randomUUID().toString();
    private final String empTablet = UUID.randomUUID().toString();
    private final String ownerPhone = UUID.randomUUID().toString();
    private final String unknownPhone = UUID.randomUUID().toString();

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        schema = "kv_" + suffix;
        new TenantSchemaProvisioner(dataSource).provision(tenantId.toString(), schema);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'PREMIUM', ?, 'ACTIVE', 'Boutique Awa')",
                tenantId, "KV-" + suffix.toUpperCase(), schema);

        // Employee: 2 devices, and 2 employee rows (e.g. two stores) — must not duplicate devices.
        device(empPhone, employee, NOW);
        device(empTablet, employee, NOW.minusSeconds(3600));
        employeeRow(employee, " Awa ", "Diallo", NOW.minusSeconds(86_400));
        employeeRow(employee, "Awa", "Doublon", NOW);
        membership(employee, "EMPLOYEE", true);

        // Owner: membership only, no employee row.
        device(ownerPhone, owner, NOW.minusSeconds(7200));
        membership(owner, "OWNER", true);

        // Unknown user: no employee row, no membership.
        device(unknownPhone, unknown, null);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM public.user_sync_state WHERE tenant_id = ?", schema);
        jdbc.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenantId);
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }

    @Test
    @DisplayName("devices carry employee name / membership role, one row per device, no duplication")
    void detail_resolvesNamesAndRoles() {
        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.devices()).extracting(AdminDeviceInfo::deviceId)
                .containsExactly(empPhone, empTablet, ownerPhone, unknownPhone);
        Map<String, AdminDeviceInfo> byId = byDeviceId(detail);

        assertThat(byId.get(empPhone).userName()).isEqualTo("Awa Diallo"); // earliest employee row
        assertThat(byId.get(empTablet).userName()).isEqualTo("Awa Diallo");
        assertThat(byId.get(empPhone).userRole()).isEqualTo("EMPLOYEE");
        assertThat(byId.get(ownerPhone).userName()).isNull();
        assertThat(byId.get(ownerPhone).userRole()).isEqualTo("OWNER");
        assertThat(byId.get(unknownPhone).userName()).isNull();
        assertThat(byId.get(unknownPhone).userRole()).isNull();
    }

    @Test
    @DisplayName("a failing name lookup (no employees table) keeps devices and roles, names null")
    void detail_failingNameLookup_keepsDevices() {
        jdbc.execute("DROP TABLE \"" + schema + "\".employees CASCADE");

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.devices()).hasSize(4);
        assertThat(detail.devices()).extracting(AdminDeviceInfo::userName).containsOnlyNulls();
        assertThat(byDeviceId(detail).get(ownerPhone).userRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("an older INACTIVE employee row loses to a newer ACTIVE one")
    void detail_prefersActiveEmployeeRow() {
        UUID rehired = UUID.randomUUID();
        String rehiredPhone = UUID.randomUUID().toString();
        device(rehiredPhone, rehired, NOW.minusSeconds(10_800));
        employeeRow(rehired, "Ancien", "Nom", NOW.minusSeconds(172_800), "INACTIVE");
        employeeRow(rehired, "Nouveau", "Nom", NOW, "ACTIVE");

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(byDeviceId(detail).get(rehiredPhone).userName()).isEqualTo("Nouveau Nom");
    }

    @Test
    @DisplayName("an inactive (removed) membership yields no role, even OWNER")
    void detail_inactiveMembership_noRole() {
        UUID removed = UUID.randomUUID();
        String removedPhone = UUID.randomUUID().toString();
        device(removedPhone, removed, NOW.minusSeconds(10_800));
        membership(removed, "OWNER", false);

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(byDeviceId(detail).get(removedPhone).userRole()).isNull();
    }

    private static Map<String, AdminDeviceInfo> byDeviceId(AdminSyncTenantDetail detail) {
        return detail.devices().stream().collect(Collectors.toMap(AdminDeviceInfo::deviceId, Function.identity()));
    }

    private void device(String deviceId, UUID userId, Instant lastPush) {
        jdbc.update("INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at) VALUES (?, ?, ?, ?)",
                deviceId, userId, schema, lastPush == null ? null : Timestamp.from(lastPush));
    }

    private void employeeRow(UUID userId, String first, String last, Instant createdAt) {
        employeeRow(userId, first, last, createdAt, "ACTIVE");
    }

    private void employeeRow(UUID userId, String first, String last, Instant createdAt, String status) {
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, created_at, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)", userId, UUID.randomUUID(), first, last, Timestamp.from(createdAt), status);
    }

    private void membership(UUID userId, String role, boolean active) {
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, tenant_id, user_id) "
                + "VALUES (?, now(), now(), ?, ?, ?, ?)", UUID.randomUUID(), active, role, tenantId, userId);
    }
}

package com.keevo.admin.feedback.application.service;

import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService.FeedbackLabels;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 9-1 completion — feedback inbox labels on a real database: employee name, « Propriétaire »,
 * « Utilisateur inconnu », « Entreprise supprimée », and a broken company schema that does not
 * blank the next company's labels (no transaction around the per-schema loop).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, AdminFeedbackLabelService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Feedback inbox labels on PostgreSQL (spec 9-1)")
class AdminFeedbackLabelServicePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired AdminFeedbackLabelService service;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userIds.forEach(u -> {
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        });
        tenantIds.forEach(id -> jdbc.update("DELETE FROM public.tenants WHERE id = ?", id));
        schemas.forEach(s -> jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE"));
    }

    @Test
    void labels() {
        UUID broken = tenant("kv_kfb001", "Boutique cassée", "ACTIVE");
        UUID live = tenant("kv_kfb002", "Boutique Awa", "ACTIVE");
        tenant("kv_kfb003", "Boutique fermée", "DELETED");
        jdbc.execute("DROP TABLE kv_kfb001.employees");

        UUID brokenOwner = member(broken, "OWNER");
        UUID owner = member(live, "OWNER");
        UUID employee = member(live, "EMPLOYEE");
        UUID storeId = jdbc.queryForObject("SELECT id FROM kv_kfb002.stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO kv_kfb002.employees (user_id, store_id, first_name, last_name) VALUES (?, ?, 'Moussa', ' Kane ')",
                employee, storeId);

        FeedbackView fromBroken = feedback("kv_kfb001", brokenOwner);   // first: its schema error must not spread
        FeedbackView fromEmployee = feedback("kv_kfb002", employee);
        FeedbackView fromOwner = feedback("kv_kfb002", owner);
        FeedbackView fromStranger = feedback("kv_kfb002", UUID.randomUUID());
        FeedbackView fromDeleted = feedback("kv_kfb003", UUID.randomUUID());

        Map<UUID, FeedbackLabels> labels = service.labels(
                List.of(fromBroken, fromEmployee, fromOwner, fromStranger, fromDeleted));

        assertThat(labels.get(fromBroken.id())).isEqualTo(new FeedbackLabels("Boutique cassée", "Propriétaire"));
        assertThat(labels.get(fromEmployee.id())).isEqualTo(new FeedbackLabels("Boutique Awa", "Moussa Kane"));
        assertThat(labels.get(fromOwner.id())).isEqualTo(new FeedbackLabels("Boutique Awa", "Propriétaire"));
        assertThat(labels.get(fromStranger.id())).isEqualTo(new FeedbackLabels("Boutique Awa", "Utilisateur inconnu"));
        assertThat(labels.get(fromDeleted.id()).tenantName()).isEqualTo("Entreprise supprimée");
    }

    private static FeedbackView feedback(String schema, UUID user) {
        return new FeedbackView(UUID.randomUUID(), "BUG", "Le scanner plante", schema, user,
                "1.0", "android", "/pos", Instant.now(), "NORMAL", null, null);
    }

    private UUID tenant(String schema, String name, String status) {
        schemas.add(schema);
        provisioner.provision("tenant-" + schema, schema);
        UUID id = UUID.randomUUID();
        tenantIds.add(id);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, name, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, ?, 1, 1, 1, 'FREE', ?, ?)",
                id, schema.toUpperCase().replace('_', '-'), name, schema, status);
        return id;
    }

    private UUID member(UUID tenantId, String role) {
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        String phone = "+2439" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)",
                userId, phone, role);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, ?, ?, ?)",
                UUID.randomUUID(), role, tenantId, userId);
        return userId;
    }
}

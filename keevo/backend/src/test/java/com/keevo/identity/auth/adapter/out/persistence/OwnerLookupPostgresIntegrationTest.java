package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.OwnerRecipientResolverAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1s-14-17 — primary active owner lookup on a real PostgreSQL: deterministic (oldest active
 * OWNER membership, tie-break user id), deactivated users / memberships and EMPLOYEEs skipped.
 * Each test uses its own tenant schema/code and phones; {@code @DataJpaTest} rolls back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({UserRepositoryAdapter.class, OwnerRecipientResolverAdapter.class})
@DisplayName("Owner recipient lookup on PostgreSQL (v1s-14-17)")
class OwnerLookupPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T08:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepositoryAdapter userRepository;
    @Autowired OwnerRecipientResolverAdapter resolver;

    @Test
    @DisplayName("several active owners: always the oldest membership, stable across calls")
    void severalOwners_oldestMembershipWins_deterministic() {
        String schema = insertTenant();
        // Inserted in reverse order: the newest membership row goes in first.
        String newerPhone = randomPhone();
        String olderPhone = randomPhone();
        membership(insertUser(newerPhone, true), schema, "OWNER", true, T0.plusSeconds(3600));
        membership(insertUser(olderPhone, true), schema, "OWNER", true, T0);

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(olderPhone);
        }
        assertThat(userRepository.findOwnerByTenantSchemaName(schema)).map(User::getPhoneNumber).contains(olderPhone);
    }

    @Test
    @DisplayName("same membership created_at: tie-break on user id")
    void sameCreatedAt_tieBreakOnUserId() {
        String schema = insertTenant();
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        String phoneA = randomPhone();
        String phoneB = randomPhone();
        membership(insertUser(b, phoneB, true), schema, "OWNER", true, T0);
        membership(insertUser(a, phoneA, true), schema, "OWNER", true, T0);

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(phoneA);
    }

    @Test
    @DisplayName("oldest owner's user deactivated: the next active owner is chosen")
    void deactivatedOwnerUser_skipped() {
        String schema = insertTenant();
        String activePhone = randomPhone();
        membership(insertUser(randomPhone(), false), schema, "OWNER", true, T0);
        membership(insertUser(activePhone, true), schema, "OWNER", true, T0.plusSeconds(60));

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(activePhone);
    }

    @Test
    @DisplayName("only owner deactivated: empty")
    void onlyOwnerDeactivated_empty() {
        String schema = insertTenant();
        membership(insertUser(randomPhone(), false), schema, "OWNER", true, T0);

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).isEmpty();
        assertThat(userRepository.findOwnerByTenantSchemaName(schema)).isEmpty();
    }

    @Test
    @DisplayName("inactive OWNER membership: not selected")
    void inactiveMembership_skipped() {
        String schema = insertTenant();
        String activePhone = randomPhone();
        membership(insertUser(randomPhone(), true), schema, "OWNER", false, T0);
        membership(insertUser(activePhone, true), schema, "OWNER", true, T0.plusSeconds(60));

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(activePhone);
    }

    @Test
    @DisplayName("EMPLOYEE membership is never chosen, even when older")
    void employeeNeverChosen() {
        String schema = insertTenant();
        membership(insertUser(randomPhone(), true), schema, "EMPLOYEE", true, T0);

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).isEmpty();

        String ownerPhone = randomPhone();
        membership(insertUser(ownerPhone, true), schema, "OWNER", true, T0.plusSeconds(60));

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(ownerPhone);
    }

    @Test
    @DisplayName("owners of another tenant are never returned")
    void otherTenantOwner_ignored() {
        String schema = insertTenant();
        String other = insertTenant();
        membership(insertUser(randomPhone(), true), other, "OWNER", true, T0);

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertTenant() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String schema = "kv_" + suffix;
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status) VALUES (?, ?, ?, ?, 5, 100, 1, 'FREE', ?, 'ACTIVE')",
                UUID.randomUUID(), Timestamp.from(T0), Timestamp.from(T0), suffix, schema);
        return schema;
    }

    private UUID insertUser(String phone, boolean active) {
        return insertUser(UUID.randomUUID(), phone, active);
    }

    private UUID insertUser(UUID id, String phone, boolean active) {
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                        + "password_hash, phone_number, role) VALUES (?, ?, ?, ?, 0, 'x', ?, 'OWNER')",
                id, Timestamp.from(T0), Timestamp.from(T0), active, phone);
        return id;
    }

    private void membership(UUID userId, String schema, String role, boolean active, Instant createdAt) {
        UUID tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                        + "tenant_id, user_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt), active, role, tenantId, userId);
    }

    private static String randomPhone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}

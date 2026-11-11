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

    // ── Story 19.1 — phone-less (email/username-only) accounts ───────────────

    @Test
    @DisplayName("19.1 — oldest owner has no phone: the next owner having one is the recipient")
    void phonelessOldestOwner_nextOwnerWithPhone() {
        String schema = insertTenant();
        String phone = randomPhone();
        membership(insertEmailOnlyUser(), schema, "OWNER", true, T0);
        membership(insertUser(phone, true), schema, "OWNER", true, T0.plusSeconds(60));

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).contains(phone);
    }

    @Test
    @DisplayName("19.1 — only phone-less owners: no recipient (as before)")
    void onlyPhonelessOwner_empty() {
        String schema = insertTenant();
        membership(insertEmailOnlyUser(), schema, "OWNER", true, T0);

        assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).isEmpty();
        assertThat(userRepository.findOwnersByTenantSchemaName(schema)).hasSize(1)
                .allSatisfy(u -> assertThat(u.getPhoneNumber()).isNull());
    }

    @Test
    @DisplayName("19.1 — email-only user loads by id with its profile; a null/blank phone lookup never matches it")
    void emailOnlyUser_loads_nullPhoneNeverMatches() {
        UUID id = insertEmailOnlyUser();

        User u = userRepository.findById(id).orElseThrow();
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getPasswordHash()).isNull();
        assertThat(u.getEmail()).startsWith("Owner.").endsWith("@Example.cm");
        assertThat(u.getFirstName()).isEqualTo("Awa");
        assertThat(u.getEmailVerifiedAt()).isEqualTo(T0);

        assertThat(userRepository.findByPhoneNumber(null)).isEmpty();
        assertThat(userRepository.findByPhoneNumber("")).isEmpty();
        assertThat(userRepository.existsByPhoneNumber(null)).isFalse();
    }

    @Test
    @DisplayName("19.1 — save keeps the new columns (JPA mapping validated against V41)")
    void save_keepsNewColumns() {
        UUID id = insertEmailOnlyUser();
        User u = userRepository.findById(id).orElseThrow();

        userRepository.save(u.withLockoutState(2, null).withUsername("awa_" + id.toString().substring(0, 8)));

        User reloaded = userRepository.findById(id).orElseThrow();
        assertThat(reloaded.failedAttempts()).isEqualTo(2);
        assertThat(reloaded.getEmail()).isEqualTo(u.getEmail());
        assertThat(reloaded.getUsername()).startsWith("awa_");
        assertThat(reloaded.getLastName()).isEqualTo("Ndiaye");
        assertThat(reloaded.getPhoneNumber()).isNull();
    }

    @Test
    @DisplayName("19.1 — legacy oldest owner with a blank phone ('' or spaces): the next owner having a phone wins")
    void blankPhoneOldestOwner_nextOwnerWithPhone() {
        for (String blank : new String[] {"", "   "}) {
            String schema = insertTenant();
            String phone = randomPhone();
            // raw SQL: the domain now stores a blank phone as NULL — this keeps the legacy-row case
            membership(insertRawUser(blank, "legacy." + UUID.randomUUID() + "@x.cm", null), schema, "OWNER", true, T0);
            membership(insertUser(phone, true), schema, "OWNER", true, T0.plusSeconds(60));

            assertThat(resolver.resolveOwnerWhatsAppPhone(schema)).as("blank=[%s]", blank).contains(phone);
            assertThat(userRepository.findOwnersByTenantSchemaName(schema)).first()
                    .extracting(User::getPhoneNumber).isEqualTo(phone);
        }
    }

    @Test
    @DisplayName("19.1 — whitespace-only phone lookup never matches, even a legacy row with that phone")
    void whitespacePhoneLookup_neverMatches() {
        insertRawUser("    ", "ws." + UUID.randomUUID() + "@x.cm", null);

        assertThat(userRepository.findByPhoneNumber("    ")).isEmpty();
        assertThat(userRepository.existsByPhoneNumber("    ")).isFalse();
    }

    @Test
    @DisplayName("19.1 — username-only user (no phone, no password) loads through JPA findById")
    void usernameOnlyUser_loadsThroughJpa() {
        String username = "awa_" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = insertRawUser(null, null, username);

        User u = userRepository.findById(id).orElseThrow();
        assertThat(u.getUsername()).isEqualTo(username);
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getEmail()).isNull();
        assertThat(u.hasPassword()).isFalse();
    }

    @Test
    @DisplayName("19.1 — OWNERS_BY_SCHEMA_SQL maps the new columns; owners with a phone first")
    void ownersBySchema_mapsNewColumns() {
        String schema = insertTenant();
        UUID emailOnly = insertEmailOnlyUser();
        String phone = randomPhone();
        membership(emailOnly, schema, "OWNER", true, T0);
        membership(insertUser(phone, true), schema, "OWNER", true, T0.plusSeconds(60));

        var owners = userRepository.findOwnersByTenantSchemaName(schema);
        assertThat(owners).hasSize(2);
        assertThat(owners.get(0).getPhoneNumber()).isEqualTo(phone);
        User mapped = owners.get(1);
        assertThat(mapped.getId()).isEqualTo(emailOnly);
        assertThat(mapped.getEmail()).isEqualTo("Owner." + emailOnly + "@Example.cm");
        assertThat(mapped.getFirstName()).isEqualTo("Awa");
        assertThat(mapped.getLastName()).isEqualTo("Ndiaye");
        assertThat(mapped.getEmailVerifiedAt()).isEqualTo(T0);
        assertThat(mapped.getPhoneVerifiedAt()).isNull();
        assertThat(mapped.getUsername()).isNull();
        assertThat(mapped.getPasswordHash()).isNull();
    }

    // ── Story 19.11b — batch lookup (platform geography screen) ───────────────

    @Test
    @DisplayName("19.11b — batch: same owner as the single lookup for every tenant, in one query")
    void batch_sameRuleAsSingleLookup() {
        // A: phone-less oldest owner, then an inactive user, then the expected owner.
        String a = insertTenant();
        String expectedA = randomPhone();
        membership(insertEmailOnlyUser(), a, "OWNER", true, T0);
        membership(insertUser(randomPhone(), false), a, "OWNER", true, T0.plusSeconds(30));
        membership(insertUser(expectedA, true), a, "OWNER", true, T0.plusSeconds(60));
        // B: inactive membership first, then the expected owner; an EMPLOYEE older than both.
        String b = insertTenant();
        String expectedB = randomPhone();
        membership(insertUser(randomPhone(), true), b, "EMPLOYEE", true, T0.minusSeconds(60));
        membership(insertUser(randomPhone(), true), b, "OWNER", false, T0);
        membership(insertUser(expectedB, true), b, "OWNER", true, T0.plusSeconds(60));
        // C: only a phone-less owner → no recipient. D: no owner at all.
        String c = insertTenant();
        membership(insertEmailOnlyUser(), c, "OWNER", true, T0);
        String d = insertTenant();

        var phones = resolver.resolveOwnerWhatsAppPhones(java.util.Arrays.asList(a, b, c, d, null, " ", "kv_x,kv_y"));

        assertThat(phones).containsOnly(java.util.Map.entry(a, expectedA), java.util.Map.entry(b, expectedB));
        for (String schema : java.util.List.of(a, b, c, d)) {
            assertThat(java.util.Optional.ofNullable(phones.get(schema)))
                    .isEqualTo(resolver.resolveOwnerWhatsAppPhone(schema));
            assertThat(java.util.Optional.ofNullable(
                    userRepository.findPrimaryOwnersByTenantSchemaNames(java.util.List.of(schema)).get(schema))
                    .map(User::getId))
                    .isEqualTo(userRepository.findOwnerByTenantSchemaName(schema).map(User::getId));
        }
        assertThat(resolver.resolveOwnerWhatsAppPhones(java.util.List.of())).isEmpty();
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

    private UUID insertRawUser(String phone, String email, String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, role, "
                        + "phone_number, email, username) VALUES (?, ?, ?, true, 0, 'OWNER', ?, ?, ?)",
                id, Timestamp.from(T0), Timestamp.from(T0), phone, email, username);
        return id;
    }

    private UUID insertEmailOnlyUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, role, email, "
                        + "first_name, last_name, email_verified_at) VALUES (?, ?, ?, true, 0, 'OWNER', ?, 'Awa', "
                        + "'Ndiaye', ?)",
                id, Timestamp.from(T0), Timestamp.from(T0), "Owner." + id + "@Example.cm", Timestamp.from(T0));
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

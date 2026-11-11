package com.keevo.identity.legacyimport.adapter.out.persistence;

import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.Role;
import com.keevo.identity.legacyimport.application.port.LegacyIdentityStore;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — JDBC implementation of {@link LegacyIdentityStore}: one query for the conflicts, one set-based
 * {@code INSERT … SELECT FROM unnest(…) ON CONFLICT DO NOTHING RETURNING} per table (one round trip, the ids actually
 * inserted come back — no reliance on per-statement batch counts). Instants travel as ISO-8601 text cast to
 * {@code timestamptz} (no JVM time-zone in the way).
 */
@Component
class JdbcLegacyIdentityStore implements LegacyIdentityStore {

    /**
     * Every Keevo account matching one of the imported identifiers, through the index of each identifier (UNION ALL
     * of index lookups joined on the unnested input — never a scan of {@code users} per account). A username is also
     * compared, in its E.164 form when it reads as a phone, to the existing phones, and a phone to the existing
     * usernames (sign-in ambiguity both ways).
     */
    static final String CONFLICTS_SQL = """
            WITH a(id, phone, email, username, username_phone) AS (
                SELECT * FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[]))
            SELECT a.id AS account_id, 'SAME_ID' AS kind
              FROM a JOIN public.users u ON u.id = a.id
            UNION ALL
            SELECT a.id, 'PHONE' FROM a JOIN public.users u ON u.phone_number = a.phone
             WHERE a.phone IS NOT NULL AND u.id <> a.id
            UNION ALL
            SELECT a.id, 'PHONE' FROM a JOIN public.users u ON lower(u.username) = lower(a.phone)
             WHERE a.phone IS NOT NULL AND u.id <> a.id
            UNION ALL
            SELECT a.id, 'EMAIL' FROM a JOIN public.users u ON lower(u.email) = lower(a.email)
             WHERE a.email IS NOT NULL AND u.id <> a.id
            UNION ALL
            SELECT a.id, 'USERNAME' FROM a JOIN public.users u ON lower(u.username) = lower(a.username)
             WHERE a.username IS NOT NULL AND u.id <> a.id
            UNION ALL
            SELECT a.id, 'USERNAME_IS_PHONE' FROM a JOIN public.users u ON u.phone_number = a.username_phone
             WHERE a.username_phone IS NOT NULL AND u.id <> a.id
            """;

    static final String INSERT_USERS_SQL = """
            INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash,
                                      phone_number, role, email, username, first_name, last_name,
                                      email_verified_at, phone_verified_at)
            SELECT id, created_at::timestamptz, updated_at::timestamptz, is_active, 0, password_hash, phone_number,
                   role, email, username, first_name, last_name, email_verified_at::timestamptz,
                   phone_verified_at::timestamptz
              FROM unnest(?::uuid[], ?::text[], ?::text[], ?::boolean[], ?::varchar[], ?::varchar[], ?::varchar[],
                          ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::text[])
                   AS x(id, created_at, updated_at, is_active, password_hash, phone_number, role, email, username,
                        first_name, last_name, email_verified_at, phone_verified_at)
            ON CONFLICT (id) DO NOTHING
            RETURNING id
            """;

    static final String INSERT_MEMBERSHIPS_SQL = """
            INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, tenant_id, user_id)
            SELECT gen_random_uuid(), created_at::timestamptz, updated_at::timestamptz, is_active, role, ?, user_id
              FROM unnest(?::uuid[], ?::text[], ?::text[], ?::boolean[], ?::varchar[])
                   AS x(user_id, created_at, updated_at, is_active, role)
            ON CONFLICT (user_id, tenant_id) DO NOTHING
            RETURNING user_id, id
            """;

    /** Story 24.2 — the suspension trace of an imported tenant, set once (the admin drawer shows it). */
    static final String TRACE_SUSPENSION_SQL = """
            UPDATE public.tenants SET suspended_at = COALESCE(suspended_at, now()),
                                      suspended_reason = COALESCE(suspended_reason, ?)
             WHERE id = ? AND status = 'SUSPENDED' AND (suspended_at IS NULL OR suspended_reason IS NULL)
            """;

    private final JdbcTemplate jdbc;

    JdbcLegacyIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Set<ConflictKind>> findConflicts(Collection<LegacyAccount> accounts) {
        List<LegacyAccount> list = List.copyOf(accounts);
        Map<UUID, Set<ConflictKind>> found = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(CONFLICTS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", list.stream().map(LegacyAccount::id).toArray()));
            ps.setArray(2, varchars(con, list.stream().map(LegacyAccount::phoneNumber).toList()));
            ps.setArray(3, varchars(con, list.stream().map(LegacyAccount::email).toList()));
            ps.setArray(4, varchars(con, list.stream().map(LegacyAccount::username).toList()));
            ps.setArray(5, varchars(con, list.stream().map(a -> usernameAsPhone(a.username())).toList()));
            return ps;
        }, rs -> {
            UUID account = rs.getObject("account_id", UUID.class);
            found.computeIfAbsent(account, k -> EnumSet.noneOf(ConflictKind.class))
                    .add(ConflictKind.valueOf(rs.getString("kind")));
        });
        return found;
    }

    /** A username made of digits only, read as a phone (E.164, Cameroonian local numbers); else {@code null}. */
    static String usernameAsPhone(String username) {
        return username != null && username.matches("\\+?[0-9]{8,15}")
                ? PhoneNumbers.toE164(username, PhoneNumbers.CAMEROON) : null;
    }

    @Override
    public Set<UUID> insertAccounts(List<LegacyAccount> accounts, List<Role> roles, List<Boolean> active) {
        if (accounts.isEmpty()) {
            return Set.of();
        }
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_USERS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", accounts.stream().map(LegacyAccount::id).toArray()));
            ps.setArray(2, texts(con, accounts.stream().map(a -> iso(a.createdAt())).toList()));
            ps.setArray(3, texts(con, accounts.stream().map(a -> iso(a.updatedAt())).toList()));
            ps.setArray(4, con.createArrayOf("boolean", active.toArray()));
            ps.setArray(5, varchars(con, accounts.stream().map(LegacyAccount::passwordHash).toList()));
            ps.setArray(6, varchars(con, accounts.stream().map(LegacyAccount::phoneNumber).toList()));
            ps.setArray(7, varchars(con, roles.stream().map(Role::name).toList()));
            ps.setArray(8, varchars(con, accounts.stream().map(LegacyAccount::email).toList()));
            ps.setArray(9, varchars(con, accounts.stream().map(LegacyAccount::username).toList()));
            ps.setArray(10, varchars(con, accounts.stream().map(LegacyAccount::firstName).toList()));
            ps.setArray(11, varchars(con, accounts.stream().map(LegacyAccount::lastName).toList()));
            ps.setArray(12, texts(con, accounts.stream().map(a -> iso(a.emailVerifiedAt())).toList()));
            ps.setArray(13, texts(con, accounts.stream().map(a -> iso(a.phoneVerifiedAt())).toList()));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public Map<UUID, UUID> insertMemberships(UUID tenantId, List<LegacyAccount> accounts, List<Role> roles,
                                             List<Boolean> active) {
        if (accounts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> inserted = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_MEMBERSHIPS_SQL);
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", accounts.stream().map(LegacyAccount::id).toArray()));
            ps.setArray(3, texts(con, accounts.stream().map(a -> iso(a.createdAt())).toList()));
            ps.setArray(4, texts(con, accounts.stream().map(a -> iso(a.updatedAt())).toList()));
            ps.setArray(5, con.createArrayOf("boolean", active.toArray()));
            ps.setArray(6, varchars(con, roles.stream().map(Role::name).toList()));
            return ps;
        }, rs -> {
            inserted.put(rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public Set<UUID> insertEmployees(String tenantSchema, List<ImportedMember> members) {
        if (members.isEmpty()) {
            return Set.of();
        }
        String table = "\"" + TenantSchema.validate(tenantSchema) + "\".employees";
        String sql = "INSERT INTO " + table + " (id, created_at, updated_at, first_name, last_name, "
                + "password_change_required, status, store_id, user_id) "
                + "SELECT id, created_at::timestamptz, updated_at::timestamptz, first_name, last_name, pcr, status, "
                + "store_id, id FROM unnest(?::uuid[], ?::text[], ?::text[], ?::varchar[], ?::varchar[], "
                + "?::boolean[], ?::varchar[], ?::uuid[]) AS x(id, created_at, updated_at, first_name, last_name, "
                + "pcr, status, store_id) ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", members.stream().map(m -> m.account().id()).toArray()));
            ps.setArray(2, texts(con, members.stream().map(m -> iso(m.account().createdAt())).toList()));
            ps.setArray(3, texts(con, members.stream().map(m -> iso(m.account().updatedAt())).toList()));
            ps.setArray(4, varchars(con, members.stream().map(ImportedMember::firstName).toList()));
            ps.setArray(5, varchars(con, members.stream().map(ImportedMember::lastName).toList()));
            ps.setArray(6, con.createArrayOf("boolean",
                    members.stream().map(ImportedMember::passwordChangeRequired).toArray()));
            ps.setArray(7, varchars(con, members.stream().map(m -> m.active() ? "ACTIVE" : "INACTIVE").toList()));
            ps.setArray(8, con.createArrayOf("uuid", members.stream().map(ImportedMember::storeId).toArray()));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public boolean insertPreferencesIfAbsent(String tenantSchema, String sectorType) {
        String table = "\"" + TenantSchema.validate(tenantSchema) + "\".tenant_preferences";
        // Bref sends no scheduled report nor alert: they start off for an imported owner (FUS-UX7, decision D4)
        return jdbc.update("INSERT INTO " + table + " (id, sector_type, eod_report_enabled, weekly_report_enabled, "
                + "inventory_report_enabled, trend_notification_enabled, stock_alert_enabled) "
                + "SELECT ?, ?, false, false, false, false, false "
                + "WHERE NOT EXISTS (SELECT 1 FROM " + table + ")", UUID.randomUUID(), sectorType) == 1;
    }

    @Override
    public void traceSuspension(UUID tenantId, String reason) {
        jdbc.update(TRACE_SUSPENSION_SQL, reason, tenantId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Array varchars(Connection con, List<String> values) throws SQLException {
        return con.createArrayOf("varchar", values.toArray());
    }

    private static Array texts(Connection con, List<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray());
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }
}

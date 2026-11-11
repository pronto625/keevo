package com.keevo.identity.legacyimport.adapter.out.persistence;

import com.keevo.identity.api.LegacyIdentityImportPort.AccountMatch;
import com.keevo.identity.api.LegacyIdentityImportPort.AuthenticatorOutcome;
import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyInvitation;
import com.keevo.identity.api.LegacyIdentityImportPort.SocialIdentity;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
     * of index lookups joined on the unnested input — never a scan of {@code users} per account): id, phone
     * ({@code uk_users_phone_number}), email and username ({@code uq_users_*_lower}), the Google / Apple identity
     * ({@code uq_user_authenticators_provider_subject}). A username is also compared, in its E.164 form when it reads
     * as a phone, to the existing phones, and a phone to the existing usernames (sign-in ambiguity both ways). Story
     * 24.7: each match names the Keevo account and what the bridge needs (active, email verified, platform admin).
     */
    static final String MATCHES_SQL = """
            WITH a(id, phone, email, username, username_phone, provider, subject, phone_digits, phone_local) AS (
                SELECT * FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[],
                                     ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[])),
            m(account_id, kind, user_id) AS (
                SELECT a.id, 'SAME_ID', u.id FROM a JOIN public.users u ON u.id = a.id
                UNION ALL
                SELECT a.id, 'PHONE', u.id FROM a JOIN public.users u ON u.phone_number = a.phone
                 WHERE a.phone IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'PHONE_IS_USERNAME', u.id FROM a JOIN public.users u ON lower(u.username) = a.phone_digits
                 WHERE a.phone_digits IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'PHONE_IS_USERNAME', u.id FROM a JOIN public.users u ON lower(u.username) = a.phone_local
                 WHERE a.phone_local IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'EMAIL', u.id FROM a JOIN public.users u ON lower(u.email) = lower(a.email)
                 WHERE a.email IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'USERNAME', u.id FROM a JOIN public.users u ON lower(u.username) = lower(a.username)
                 WHERE a.username IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'USERNAME_IS_PHONE', u.id FROM a JOIN public.users u ON u.phone_number = a.username_phone
                 WHERE a.username_phone IS NOT NULL AND u.id <> a.id
                UNION ALL
                SELECT a.id, 'SOCIAL', ua.user_id FROM a
                  JOIN public.user_authenticators ua ON ua.provider = a.provider AND ua.subject = a.subject
                 WHERE a.provider IS NOT NULL AND ua.user_id <> a.id)
            SELECT m.account_id, m.kind, m.user_id, u.is_active, u.email_verified_at IS NOT NULL AS email_verified,
                   u.role = 'SUPER_ADMIN' AS platform_admin
              FROM m JOIN public.users u ON u.id = m.user_id
            """;

    /**
     * Story 24.7 — PENDING invitations of existing accounts (19.6b), identified by the phone typed in Bref; no message
     * (last_sent_at = the invitation time, so the 19.6b « one message per 24 h » rule stays unchanged).
     */
    static final String INSERT_INVITATIONS_SQL = """
            INSERT INTO public.membership_invitations (id, tenant_id, user_id, role, store_id, first_name, last_name,
                                                      identifier_kind, identifier, invited_by, status, invited_at,
                                                      expires_at, responded_at, last_sent_at)
            SELECT gen_random_uuid(), ?, user_id, role, store_id, first_name, last_name, 'PHONE', phone, ?, 'PENDING',
                   now(), now() + make_interval(secs => ?), NULL, now()
              FROM unnest(?::uuid[], ?::varchar[], ?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[])
                   AS x(user_id, role, store_id, first_name, last_name, phone)
            ON CONFLICT (tenant_id, user_id) DO NOTHING
            RETURNING user_id, id
            """;

    /**
     * Story 24.7 — Google / Apple identities in one statement: inserted when nobody holds the identity and the account
     * has none of that provider (19.3b: one identity per provider). The outer query reads the table as it was before
     * the insert (one snapshot), so the previous holder tells « already linked » from « held by another account ».
     */
    static final String INSERT_AUTHENTICATORS_SQL = """
            WITH x AS (
                SELECT * FROM unnest(?::uuid[], ?::varchar[], ?::varchar[]) WITH ORDINALITY
                       AS x(user_id, provider, subject, n)),
            ins AS (
                INSERT INTO public.user_authenticators (id, user_id, provider, subject, created_at)
                SELECT gen_random_uuid(), x.user_id, x.provider, x.subject, now() FROM x
                 WHERE NOT EXISTS (SELECT 1 FROM public.user_authenticators o
                                    WHERE o.user_id = x.user_id AND o.provider = x.provider)
                ON CONFLICT (provider, subject) DO NOTHING
                RETURNING id, provider, subject)
            SELECT x.n, ins.id AS inserted_id, h.user_id AS holder
              FROM x LEFT JOIN ins ON ins.provider = x.provider AND ins.subject = x.subject
                     LEFT JOIN public.user_authenticators h ON h.provider = x.provider AND h.subject = x.subject
             ORDER BY x.n
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
    public Map<UUID, List<AccountMatch>> findMatches(Collection<LegacyAccount> accounts) {
        List<LegacyAccount> list = List.copyOf(accounts);
        Map<UUID, List<AccountMatch>> found = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(MATCHES_SQL);
            ps.setArray(1, con.createArrayOf("uuid", list.stream().map(LegacyAccount::id).toArray()));
            ps.setArray(2, varchars(con, list.stream().map(LegacyAccount::phoneNumber).toList()));
            ps.setArray(3, varchars(con, list.stream().map(LegacyAccount::email).toList()));
            ps.setArray(4, varchars(con, list.stream().map(LegacyAccount::username).toList()));
            ps.setArray(5, varchars(con, list.stream().map(a -> usernameAsPhone(a.username())).toList()));
            ps.setArray(6, varchars(con, list.stream().map(LegacyAccount::socialProvider).toList()));
            ps.setArray(7, varchars(con, list.stream().map(LegacyAccount::socialSubject).toList()));
            ps.setArray(8, varchars(con, list.stream().map(a -> phoneDigits(a.phoneNumber())).toList()));
            ps.setArray(9, varchars(con, list.stream().map(a -> phoneLocal(a.phoneNumber())).toList()));
            return ps;
        }, rs -> {
            UUID account = rs.getObject("account_id", UUID.class);
            found.computeIfAbsent(account, k -> new ArrayList<>()).add(new AccountMatch(
                    ConflictKind.valueOf(rs.getString("kind")), rs.getObject("user_id", UUID.class),
                    rs.getBoolean("is_active"), rs.getBoolean("email_verified"), rs.getBoolean("platform_admin")));
        });
        return found;
    }

    @Override
    public Map<UUID, UUID> insertInvitations(UUID tenantId, UUID invitedBy, List<LegacyInvitation> invitations,
                                             Duration validity) {
        if (invitations.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> inserted = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_INVITATIONS_SQL);
            ps.setObject(1, tenantId);
            ps.setObject(2, invitedBy);
            ps.setLong(3, validity.toSeconds());
            ps.setArray(4, con.createArrayOf("uuid", invitations.stream().map(LegacyInvitation::accountId).toArray()));
            ps.setArray(5, varchars(con, invitations.stream().map(i -> i.role().name()).toList()));
            ps.setArray(6, con.createArrayOf("uuid", invitations.stream().map(LegacyInvitation::storeId).toArray()));
            ps.setArray(7, varchars(con, invitations.stream().map(LegacyInvitation::firstName).toList()));
            ps.setArray(8, varchars(con, invitations.stream().map(LegacyInvitation::lastName).toList()));
            ps.setArray(9, varchars(con, invitations.stream().map(LegacyInvitation::phoneNumber).toList()));
            return ps;
        }, rs -> {
            inserted.put(rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public List<AuthenticatorOutcome> insertAuthenticators(List<SocialIdentity> identities) {
        if (identities.isEmpty()) {
            return List.of();
        }
        List<AuthenticatorOutcome> outcomes = new ArrayList<>(identities.size());
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_AUTHENTICATORS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", identities.stream().map(SocialIdentity::userId).toArray()));
            ps.setArray(2, varchars(con, identities.stream().map(SocialIdentity::provider).toList()));
            ps.setArray(3, varchars(con, identities.stream().map(SocialIdentity::subject).toList()));
            return ps;
        }, rs -> {
            SocialIdentity identity = identities.get((int) rs.getLong("n") - 1);
            UUID insertedId = rs.getObject("inserted_id", UUID.class);
            UUID holder = rs.getObject("holder", UUID.class);
            AuthenticatorOutcome.Result result;
            if (insertedId != null) {
                result = AuthenticatorOutcome.Result.INSERTED;
            } else if (identity.userId().equals(holder)) {
                result = AuthenticatorOutcome.Result.ALREADY_LINKED;
            } else if (holder != null) {
                result = AuthenticatorOutcome.Result.HELD_BY_OTHER;
            } else {
                result = AuthenticatorOutcome.Result.PROVIDER_ALREADY_LINKED;
            }
            outcomes.add(new AuthenticatorOutcome(identity, result, insertedId));
        });
        return outcomes;
    }

    /**
     * Story 24.7 — the username forms a phone can take (a username cannot hold « + », V41): the E.164 digits, and the
     * 9-digit national number of a Cameroonian phone.
     */
    static String phoneDigits(String e164) {
        return e164 == null || !e164.startsWith("+") ? null : e164.substring(1);
    }

    static String phoneLocal(String e164) {
        return e164 != null && e164.startsWith("+237") && e164.length() == 13 ? e164.substring(4) : null;
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
                + "store_id, user_id FROM unnest(?::uuid[], ?::text[], ?::text[], ?::varchar[], ?::varchar[], "
                + "?::boolean[], ?::varchar[], ?::uuid[], ?::uuid[]) AS x(id, created_at, updated_at, first_name, "
                + "last_name, pcr, status, store_id, user_id) ON CONFLICT (id) DO NOTHING RETURNING id";
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
            ps.setArray(9, con.createArrayOf("uuid", members.stream().map(ImportedMember::userId).toArray()));
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

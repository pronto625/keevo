package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.model.MembershipInvitation.IdentifierKind;
import com.keevo.identity.employee.domain.model.MembershipInvitation.Status;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.6b — {@code public.membership_invitations} over raw JDBC (fully-qualified table: the request connection's
 * search_path starts with a tenant schema). One statement per method; the state transitions are SQL guards (no
 * read-then-write race): the conditional upsert on {@code uq_membership_invitations_tenant_user}, the guarded
 * {@code UPDATE … WHERE status = 'PENDING'}, the {@code FOR UPDATE} of the acceptance. The message cooldown is carried
 * by {@code last_sent_at}, moved only when the previous message is older than the window (the upsert answers whether a
 * message is due).
 */
@Component
public class JdbcMembershipInvitationRepository implements MembershipInvitationRepository {

    private static final String COLUMNS = "i.id, i.tenant_id, i.user_id, i.role, i.store_id, i.first_name, "
            + "i.last_name, i.identifier_kind, i.identifier, i.invited_by, i.status, i.invited_at, i.expires_at";

    static final String UPSERT_SQL = "INSERT INTO public.membership_invitations AS i (id, tenant_id, user_id, role, "
            + "store_id, first_name, last_name, identifier_kind, identifier, invited_by, status, invited_at, "
            + "expires_at, responded_at, last_sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL, ?) "
            + "ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role, store_id = EXCLUDED.store_id, "
            + "first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name, "
            + "identifier_kind = EXCLUDED.identifier_kind, identifier = EXCLUDED.identifier, "
            + "invited_by = EXCLUDED.invited_by, status = 'PENDING', invited_at = EXCLUDED.invited_at, "
            + "expires_at = EXCLUDED.expires_at, responded_at = NULL, "
            + "last_sent_at = CASE WHEN i.last_sent_at < ? THEN EXCLUDED.last_sent_at ELSE i.last_sent_at END "
            + "WHERE i.status <> 'DECLINED' OR i.responded_at < ? "
            + "RETURNING i.last_sent_at = ? AS due";
    static final String COUNT_SINCE_SQL =
            "SELECT count(*) FROM public.membership_invitations WHERE tenant_id = ? AND invited_at >= ?";
    static final String PENDING_FOR_TENANT_SQL = "SELECT " + COLUMNS + " FROM public.membership_invitations i "
            + "WHERE i.tenant_id = ? AND i.status = 'PENDING' AND i.expires_at > ? "
            + "AND (CAST(? AS uuid) IS NULL OR (i.store_id = CAST(? AS uuid) AND i.role IN ('EMPLOYEE', 'VIEWER'))) "
            + "ORDER BY i.invited_at DESC, i.id";
    private static final String PENDING_FOR_USER_BASE = "SELECT " + COLUMNS
            + ", t.code AS tenant_code, t.name AS tenant_name, t.schema_name AS tenant_schema "
            + "FROM public.membership_invitations i JOIN public.tenants t ON t.id = i.tenant_id "
            + "WHERE i.user_id = ? AND i.status = 'PENDING' AND i.expires_at > ? AND t.status = 'ACTIVE'";
    static final String PENDING_FOR_USER_SQL = PENDING_FOR_USER_BASE + " ORDER BY i.invited_at DESC, i.id LIMIT 50";
    static final String PENDING_SCHEMA_SQL = "SELECT t.schema_name FROM public.membership_invitations i "
            + "JOIN public.tenants t ON t.id = i.tenant_id WHERE i.id = ? AND i.user_id = ? AND i.status = 'PENDING' "
            + "AND i.expires_at > ? AND t.status = 'ACTIVE'";
    static final String HAS_ACCEPTED_SQL = "SELECT EXISTS (SELECT 1 FROM public.membership_invitations "
            + "WHERE tenant_id = ? AND user_id = ? AND status = 'ACCEPTED')";
    static final String LOCK_FOR_USER_SQL = PENDING_FOR_USER_BASE + " AND i.id = ? FOR UPDATE OF i";
    static final String MARK_RESPONDED_SQL = "UPDATE public.membership_invitations SET status = ?, responded_at = ? "
            + "WHERE id = ? AND status = 'PENDING'";
    static final String CANCEL_SQL = "UPDATE public.membership_invitations SET status = 'CANCELLED', responded_at = ? "
            + "WHERE id = ? AND tenant_id = ? AND status = 'PENDING' AND expires_at > ? "
            + "AND (CAST(? AS uuid) IS NULL OR (store_id = CAST(? AS uuid) AND role IN ('EMPLOYEE', 'VIEWER')))";
    static final String ACCEPTED_KINDS_SQL = "SELECT user_id, identifier_kind FROM public.membership_invitations "
            + "WHERE tenant_id = ? AND status = 'ACCEPTED' AND user_id = ANY(?)";

    private static final RowMapper<MembershipInvitation> INVITATION = (rs, n) -> invitation(rs);

    private final JdbcTemplate jdbc;

    public JdbcMembershipInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean upsertPending(MembershipInvitation inv, Instant now, Instant declinedBefore, Instant sentBefore) {
        Timestamp sentAt = Timestamp.from(inv.invitedAt());
        List<Boolean> due = jdbc.query(UPSERT_SQL, (rs, n) -> rs.getBoolean("due"),
                inv.id(), inv.tenantId(), inv.userId(), inv.role(), inv.storeId(), inv.firstName(), inv.lastName(),
                inv.identifierKind().name(), inv.identifier(), inv.invitedBy(), sentAt,
                Timestamp.from(inv.expiresAt()), sentAt, Timestamp.from(sentBefore), Timestamp.from(declinedBefore),
                sentAt);
        return !due.isEmpty() && Boolean.TRUE.equals(due.get(0));
    }

    @Override
    public Optional<String> pendingTenantSchema(UUID id, UUID userId, Instant now) {
        return jdbc.queryForList(PENDING_SCHEMA_SQL, String.class, id, userId, Timestamp.from(now)).stream()
                .findFirst();
    }

    @Override
    public boolean hasAccepted(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(HAS_ACCEPTED_SQL, Boolean.class, tenantId, userId));
    }

    @Override
    public int countInvitedSince(UUID tenantId, Instant since) {
        Integer count = jdbc.queryForObject(COUNT_SINCE_SQL, Integer.class, tenantId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public List<MembershipInvitation> findPendingForTenant(UUID tenantId, UUID storeId, Instant now) {
        return jdbc.query(PENDING_FOR_TENANT_SQL, INVITATION, tenantId, Timestamp.from(now), storeId, storeId);
    }

    @Override
    public List<PendingForUser> findPendingForUser(UUID userId, Instant now) {
        return jdbc.query(PENDING_FOR_USER_SQL, (rs, n) -> pendingForUser(rs), userId, Timestamp.from(now));
    }

    @Override
    public Optional<PendingForUser> lockPendingForUser(UUID id, UUID userId, Instant now) {
        return jdbc.query(LOCK_FOR_USER_SQL, (rs, n) -> pendingForUser(rs), userId, Timestamp.from(now), id)
                .stream().findFirst();
    }

    @Override
    public boolean markResponded(UUID id, Status status, Instant now) {
        return jdbc.update(MARK_RESPONDED_SQL, status.name(), Timestamp.from(now), id) == 1;
    }

    @Override
    public boolean cancel(UUID id, UUID tenantId, UUID storeId, Instant now) {
        return jdbc.update(CANCEL_SQL, Timestamp.from(now), id, tenantId, Timestamp.from(now), storeId, storeId) == 1;
    }

    @Override
    public Map<UUID, IdentifierKind> acceptedIdentifierKinds(UUID tenantId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, IdentifierKind> kinds = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(ACCEPTED_KINDS_SQL);
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", new ArrayList<>(userIds).toArray()));
            return ps;
        }, rs -> {
            kinds.put(rs.getObject("user_id", UUID.class), IdentifierKind.valueOf(rs.getString("identifier_kind")));
        });
        return kinds;
    }

    private static PendingForUser pendingForUser(ResultSet rs) throws SQLException {
        return new PendingForUser(invitation(rs), rs.getString("tenant_code"), rs.getString("tenant_name"),
                rs.getString("tenant_schema"));
    }

    private static MembershipInvitation invitation(ResultSet rs) throws SQLException {
        return new MembershipInvitation(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("role"), rs.getObject("store_id", UUID.class),
                rs.getString("first_name"), rs.getString("last_name"),
                IdentifierKind.valueOf(rs.getString("identifier_kind")), rs.getString("identifier"),
                rs.getObject("invited_by", UUID.class), Status.valueOf(rs.getString("status")),
                rs.getTimestamp("invited_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    }
}

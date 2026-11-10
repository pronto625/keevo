package com.keevo.mcp.token.adapter.out.persistence;

import com.keevo.mcp.token.domain.model.McpAccessToken;
import com.keevo.mcp.token.domain.port.out.McpAccessTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.9 — {@link McpAccessTokenRepository} on {@code public.mcp_access_tokens} (V66). Every statement is one
 * indexed access: the hash lookup on {@code uq_mcp_access_tokens_hash} (joined to {@code tenants} by primary key for the
 * schema), the owner's list / count on {@code idx_mcp_access_tokens_owner}, the purge on
 * {@code idx_mcp_access_tokens_expires_at}. The company is resolved from its schema name in the statement (unique index
 * on {@code tenants.schema_name}).
 */
@Repository
public class JdbcMcpAccessTokenRepository implements McpAccessTokenRepository {

    private static final String COLUMNS = "k.id, k.name, k.hint, k.tools, k.created_at, k.expires_at, k.last_used_at";

    private final JdbcTemplate jdbc;

    public JdbcMcpAccessTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(String schema, UUID userId, McpAccessToken token, byte[] tokenHash) {
        int inserted = jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO public.mcp_access_tokens (id, tenant_id, user_id, name, "
                    + "token_hash, hint, tools, created_at, expires_at) "
                    + "SELECT ?, t.id, ?, ?, ?, ?, ?, ?, ? FROM public.tenants t WHERE t.schema_name = ?");
            ps.setObject(1, token.id());
            ps.setObject(2, userId);
            ps.setString(3, token.name());
            ps.setBytes(4, tokenHash);
            ps.setString(5, token.hint());
            ps.setArray(6, con.createArrayOf("text", token.tools().toArray()));
            ps.setTimestamp(7, Timestamp.from(token.createdAt()));
            ps.setTimestamp(8, Timestamp.from(token.expiresAt()));
            ps.setString(9, schema);
            return ps;
        });
        return inserted == 1;
    }

    @Override
    public List<McpAccessToken> list(String schema, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM public.mcp_access_tokens k "
                        + "WHERE k.tenant_id = (SELECT t.id FROM public.tenants t WHERE t.schema_name = ?) "
                        + "AND k.user_id = ? ORDER BY k.created_at DESC, k.id",
                (rs, i) -> token(rs), schema, userId);
    }

    @Override
    public int count(String schema, UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM public.mcp_access_tokens k "
                + "WHERE k.tenant_id = (SELECT t.id FROM public.tenants t WHERE t.schema_name = ?) "
                + "AND k.user_id = ?", Integer.class, schema, userId);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<String> delete(String schema, UUID userId, UUID tokenId) {
        return jdbc.query("DELETE FROM public.mcp_access_tokens k WHERE k.id = ? AND k.user_id = ? "
                        + "AND k.tenant_id = (SELECT t.id FROM public.tenants t WHERE t.schema_name = ?) "
                        + "RETURNING k.name",
                (rs, i) -> rs.getString(1), tokenId, userId, schema).stream().findFirst();
    }

    @Override
    public int deleteCutOff(String schema, UUID userId) {
        return jdbc.update("DELETE FROM public.mcp_access_tokens k USING public.tenants t, "
                + "public.user_tenant_memberships m WHERE t.schema_name = ? AND k.tenant_id = t.id AND k.user_id = ? "
                + "AND m.tenant_id = t.id AND m.user_id = k.user_id AND m.tokens_valid_after IS NOT NULL "
                + "AND k.created_at < m.tokens_valid_after", schema, userId);
    }

    @Override
    public Optional<Credential> findByHash(byte[] tokenHash) {
        List<Credential> rows = jdbc.query("SELECT k.id, k.user_id, t.schema_name, k.token_hash, k.tools, "
                        + "k.created_at, k.expires_at, k.last_used_at "
                        + "FROM public.mcp_access_tokens k JOIN public.tenants t ON t.id = k.tenant_id "
                        + "WHERE k.token_hash = ?",
                (rs, i) -> new Credential(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("schema_name"), rs.getBytes("token_hash"), tools(rs.getArray("tools")),
                        instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at")),
                (Object) tokenHash);
        return rows.stream().findFirst();
    }

    @Override
    public void touch(UUID tokenId, Instant usedAt, Instant staleBefore) {
        jdbc.update("UPDATE public.mcp_access_tokens SET last_used_at = ? WHERE id = ? "
                + "AND (last_used_at IS NULL OR last_used_at < ?)",
                Timestamp.from(usedAt), tokenId, Timestamp.from(staleBefore));
    }

    @Override
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM public.mcp_access_tokens WHERE expires_at < ?", Timestamp.from(cutoff));
    }

    private static McpAccessToken token(ResultSet rs) throws SQLException {
        return new McpAccessToken(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("hint"),
                tools(rs.getArray("tools")), instant(rs, "created_at"), instant(rs, "expires_at"),
                instant(rs, "last_used_at"));
    }

    private static List<String> tools(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

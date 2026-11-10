package com.keevo.whatsappcloud.account.adapter.out.persistence;

import com.keevo.whatsappcloud.account.domain.model.RoutedAccount;
import com.keevo.whatsappcloud.account.domain.model.WhatsAppAccount;
import com.keevo.whatsappcloud.account.domain.port.out.WhatsAppAccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.5 — {@link WhatsAppAccountRepository} on {@code public.whatsapp_accounts} (V68), plain JDBC. Every statement
 * is one indexed access: the company by its unique schema name joined to the primary key, the webhook routing by
 * {@code uq_whatsapp_accounts_phone_number_id} / {@code idx_whatsapp_accounts_waba} with {@code = ANY(?)} (one round
 * trip for every number of a delivery), joined to {@code tenants} by primary key to skip deleted companies.
 */
@Component
public class JdbcWhatsAppAccountRepository implements WhatsAppAccountRepository {

    private static final String COLUMNS = "a.tenant_id, t.schema_name, a.waba_id, a.phone_number_id, "
            + "a.display_phone_number, a.verified_name, a.access_token_enc, a.connected_by, a.connected_at";

    private static final RowMapper<WhatsAppAccount> ACCOUNT = (rs, n) -> new WhatsAppAccount(
            rs.getObject("tenant_id", UUID.class), rs.getString("schema_name"), rs.getString("waba_id"),
            rs.getString("phone_number_id"), rs.getString("display_phone_number"), rs.getString("verified_name"),
            rs.getBytes("access_token_enc"), rs.getObject("connected_by", UUID.class),
            rs.getTimestamp("connected_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcWhatsAppAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WhatsAppAccount> find(String tenantSchema) {
        return jdbc.query("SELECT " + COLUMNS + " FROM public.whatsapp_accounts a JOIN public.tenants t ON t.id = "
                + "a.tenant_id WHERE t.schema_name = ?", ACCOUNT, tenantSchema).stream().findFirst();
    }

    @Override
    public List<RoutedAccount> routeByPhoneNumberIds(Collection<String> phoneNumberIds) {
        if (phoneNumberIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT a.phone_number_id AS k, a.tenant_id, t.schema_name FROM "
                    + "public.whatsapp_accounts a JOIN public.tenants t ON t.id = a.tenant_id "
                    + "WHERE a.phone_number_id = ANY(?) AND t.status <> 'DELETED'");
            ps.setArray(1, con.createArrayOf("varchar", phoneNumberIds.toArray()));
            return ps;
        }, (rs, n) -> new RoutedAccount(rs.getString("k"), rs.getObject("tenant_id", UUID.class),
                rs.getString("schema_name")));
    }

    @Override
    public List<RoutedAccount> routeByWabaIds(Collection<String> wabaIds) {
        if (wabaIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT a.waba_id AS k, a.tenant_id, t.schema_name FROM "
                    + "public.whatsapp_accounts a JOIN public.tenants t ON t.id = a.tenant_id "
                    + "WHERE a.waba_id = ANY(?) AND t.status <> 'DELETED'");
            ps.setArray(1, con.createArrayOf("varchar", wabaIds.toArray()));
            return ps;
        }, (rs, n) -> new RoutedAccount(rs.getString("k"), rs.getObject("tenant_id", UUID.class),
                rs.getString("schema_name")));
    }

    @Override
    public boolean save(WhatsAppAccount a) {
        try {
            jdbc.update("INSERT INTO public.whatsapp_accounts (tenant_id, waba_id, phone_number_id, "
                            + "display_phone_number, verified_name, access_token_enc, connected_by, connected_at, "
                            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET "
                            + "waba_id = EXCLUDED.waba_id, phone_number_id = EXCLUDED.phone_number_id, "
                            + "display_phone_number = EXCLUDED.display_phone_number, "
                            + "verified_name = EXCLUDED.verified_name, access_token_enc = EXCLUDED.access_token_enc, "
                            + "connected_by = EXCLUDED.connected_by, connected_at = EXCLUDED.connected_at, "
                            + "updated_at = EXCLUDED.updated_at",
                    a.tenantId(), a.wabaId(), a.phoneNumberId(), a.displayPhoneNumber(), a.verifiedName(),
                    a.tokenEnc(), a.connectedBy(), Timestamp.from(a.connectedAt()), Timestamp.from(a.connectedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false; // uq_whatsapp_accounts_phone_number_id: the number belongs to another company
        }
    }

    @Override
    public void deleteOfDeletedTenants(String phoneNumberId) {
        jdbc.update("DELETE FROM public.whatsapp_accounts a USING public.tenants t WHERE t.id = a.tenant_id "
                + "AND a.phone_number_id = ? AND t.status = 'DELETED'", phoneNumberId);
    }

    @Override
    public Optional<WhatsAppAccount> delete(String tenantSchema) {
        return jdbc.query("DELETE FROM public.whatsapp_accounts a USING public.tenants t WHERE t.id = a.tenant_id "
                + "AND t.schema_name = ? RETURNING " + COLUMNS, ACCOUNT, tenantSchema).stream().findFirst();
    }
}

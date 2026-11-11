package com.keevo.whatsappcloud.platform.adapter.out;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Story 21.6 — the numbers that answered STOP to the platform number ({@code public.whatsapp_marketing_opt_outs},
 * primary key = the digits): one statement per STOP / START, one {@code = ANY} lookup per campaign batch.
 */
@Component
public class JdbcPlatformOptOutRepository implements com.keevo.whatsappcloud.platform.domain.port.out.PlatformOptOutRepository {

    private final JdbcTemplate jdbc;

    public JdbcPlatformOptOutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** STOP: recorded once (an earlier STOP keeps its date). */
    @Override
    public boolean optOut(String phone, Instant at) {
        return jdbc.update("INSERT INTO public.whatsapp_marketing_opt_outs (phone, opted_out_at) VALUES (?, ?) "
                + "ON CONFLICT (phone) DO NOTHING", phone, Timestamp.from(at)) == 1;
    }

    /** START: removed. */
    @Override
    public boolean optIn(String phone) {
        return jdbc.update("DELETE FROM public.whatsapp_marketing_opt_outs WHERE phone = ?", phone) == 1;
    }

    @Override
    public Set<String> optedOut(Collection<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return Set.of();
        }
        String[] array = phones.toArray(String[]::new);
        return new HashSet<>(jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT phone FROM public.whatsapp_marketing_opt_outs WHERE phone = ANY(?)");
            ps.setArray(1, con.createArrayOf("varchar", array));
            return ps;
        }, (rs, n) -> rs.getString(1)));
    }
}

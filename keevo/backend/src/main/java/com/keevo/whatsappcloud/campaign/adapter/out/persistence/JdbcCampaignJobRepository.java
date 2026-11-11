package com.keevo.whatsappcloud.campaign.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — {@link CampaignJobRepository} on {@code public.whatsapp_campaign_jobs}. The claim is one statement:
 * the due rows ({@code idx_whatsapp_campaign_jobs_due}, oldest first, LIMIT) locked with SKIP LOCKED, their next run
 * pushed to the lease, joined to the live companies for their schema.
 */
@Component
public class JdbcCampaignJobRepository implements CampaignJobRepository {

    private final JdbcTemplate jdbc;

    public JdbcCampaignJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void schedule(String tenantSchema, UUID campaignId, Instant runAt) {
        jdbc.update("INSERT INTO public.whatsapp_campaign_jobs (campaign_id, tenant_id, next_run_at) "
                        + "SELECT ?, t.id, ? FROM public.tenants t WHERE t.schema_name = ? "
                        + "ON CONFLICT (campaign_id) DO UPDATE SET next_run_at = EXCLUDED.next_run_at",
                campaignId, Timestamp.from(runAt), TenantSchema.validate(tenantSchema));
    }

    @Override
    public void remove(UUID campaignId) {
        jdbc.update("DELETE FROM public.whatsapp_campaign_jobs WHERE campaign_id = ?", campaignId);
    }

    @Override
    public List<Job> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<Job> jobs = jdbc.query("UPDATE public.whatsapp_campaign_jobs j SET next_run_at = ? FROM public.tenants t "
                        + "WHERE j.campaign_id IN (SELECT q.campaign_id FROM public.whatsapp_campaign_jobs q JOIN "
                        + "public.tenants qt ON qt.id = q.tenant_id AND qt.status <> 'DELETED' WHERE q.next_run_at <= ? "
                        + "ORDER BY q.next_run_at LIMIT ? FOR UPDATE OF q SKIP LOCKED) AND t.id = j.tenant_id RETURNING j.campaign_id, j.tenant_id, t.schema_name",
                (rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
                Timestamp.from(leaseUntil), Timestamp.from(now), limit);
        return jobs.stream().filter(j -> TenantSchema.isValid(j.tenantSchema())).toList();
    }

    @Override
    public void reschedule(UUID campaignId, Instant runAt) {
        jdbc.update("UPDATE public.whatsapp_campaign_jobs SET next_run_at = ? WHERE campaign_id = ?",
                Timestamp.from(runAt), campaignId);
    }
}

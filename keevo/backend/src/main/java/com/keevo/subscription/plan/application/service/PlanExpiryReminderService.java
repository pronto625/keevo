package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.subscription.plan.domain.port.in.SendPlanExpiryRemindersUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PlanExpiryReminderService — WhatsApp reminders before a PREMIUM or PREMIUM_TRIAL expiry
 * (Story 9-2, FR81).
 *
 * <p>For every live, non-FREE tenant (not DELETED / DELETION_PENDING, not the system tenant) whose
 * schema's ACTIVE PREMIUM / PREMIUM_TRIAL subscription expires within 7 WAT calendar days, the
 * reminder threshold is 7 when {@code 1 < daysLeft <= 7} and 1 when {@code 0 <= daysLeft <= 1}
 * (a missed run never skips a threshold). The reminder is first claimed in
 * {@code public.plan_expiry_reminders} ({@code ON CONFLICT DO NOTHING} on tenant, threshold,
 * expiry date) and sent only when the claim inserted a row — at most once per key. When nothing
 * could be sent (no owner phone, send failure) the claim is deleted so the next run retries.
 *
 * <p>Per-tenant SQL/data errors skip the tenant; connection/resource failures are rethrown.
 */
@Service
public class PlanExpiryReminderService implements SendPlanExpiryRemindersUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryReminderService.class);

    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants "
            + "WHERE status NOT IN ('DELETED', 'DELETION_PENDING') AND plan_type <> 'FREE' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";

    static final String EXPIRY_SQL =
            "SELECT plan_type, expires_at FROM \"%s\".subscriptions "
            + "WHERE plan_type IN ('PREMIUM', 'PREMIUM_TRIAL') AND status = 'ACTIVE' AND expires_at IS NOT NULL "
            + "ORDER BY created_at ASC LIMIT 1";

    static final String CLAIM_SQL =
            "INSERT INTO public.plan_expiry_reminders (tenant_id, threshold_days, expiry_date) "
            + "VALUES (?, ?, ?) ON CONFLICT (tenant_id, threshold_days, expiry_date) DO NOTHING";

    static final String UNCLAIM_SQL =
            "DELETE FROM public.plan_expiry_reminders "
            + "WHERE tenant_id = ? AND threshold_days = ? AND expiry_date = ?";

    private record Expiry(PlanType plan, LocalDate date) {}

    private final JdbcTemplate jdbc;
    private final WhatsAppPort whatsAppPort;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final Clock clock;

    public PlanExpiryReminderService(JdbcTemplate jdbc, WhatsAppPort whatsAppPort,
                                     OwnerRecipientResolver ownerRecipientResolver, Clock clock) {
        this.jdbc = jdbc;
        this.whatsAppPort = whatsAppPort;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.clock = clock;
    }

    /** Threshold to claim for a distance in days, or null when outside the reminder window. */
    static Integer thresholdFor(long daysLeft) {
        if (daysLeft < 0 || daysLeft > 7) return null;
        return daysLeft <= 1 ? 1 : 7;
    }

    /** @return number of reminders sent by this run */
    @Override
    public int execute() {
        LocalDate today = LocalDate.now(clock.withZone(PlanWhatsAppMessages.WAT));
        int sent = 0;
        for (Map<String, Object> tenant : jdbc.queryForList(FETCH_TENANTS)) {
            String schema = (String) tenant.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue;
            UUID tenantId = tenant.get("id") instanceof UUID u ? u : UUID.fromString(String.valueOf(tenant.get("id")));
            try {
                if (remind(tenantId, schema, today)) sent++;
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e; // outage / pool exhaustion: stop the run, the next one retries
            } catch (DataAccessException e) {
                log.warn("Plan expiry reminder: tenant {} skipped ({})", tenantId, e.getClass().getSimpleName());
            }
        }
        log.info("PlanExpiryReminderService: {} reminder(s) sent", sent);
        return sent;
    }

    private boolean remind(UUID tenantId, String schema, LocalDate today) {
        Optional<Expiry> expiry = expiry(schema);
        if (expiry.isEmpty()) return false;
        long daysLeft = ChronoUnit.DAYS.between(today, expiry.get().date());
        Integer threshold = thresholdFor(daysLeft);
        if (threshold == null) return false;

        if (jdbc.update(CLAIM_SQL, tenantId, threshold, expiry.get().date()) == 0) {
            return false; // already reminded for this threshold and expiry date
        }
        if (send(tenantId, schema, expiry.get(), threshold)) return true;
        jdbc.update(UNCLAIM_SQL, tenantId, threshold, expiry.get().date()); // retry on the next run
        return false;
    }

    private Optional<Expiry> expiry(String schema) {
        List<Map<String, Object>> rows = jdbc.queryForList(String.format(EXPIRY_SQL, schema));
        if (rows.isEmpty() || rows.get(0).get("expires_at") == null) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        Object exp = row.get("expires_at");
        java.time.Instant instant = exp instanceof Timestamp ts ? ts.toInstant()
                : exp instanceof java.time.OffsetDateTime odt ? odt.toInstant() : (java.time.Instant) exp;
        return Optional.of(new Expiry(PlanType.valueOf((String) row.get("plan_type")),
                instant.atZone(PlanWhatsAppMessages.WAT).toLocalDate()));
    }

    /** @return true when the message was handed to WhatsApp */
    private boolean send(UUID tenantId, String schema, Expiry expiry, int threshold) {
        try {
            Optional<String> phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(schema);
            if (phone.isEmpty()) {
                log.warn("Plan expiry reminder ({} d) skipped for tenant {}: no owner WhatsApp phone — retried next run",
                        threshold, tenantId);
                return false;
            }
            String text = expiry.plan() == PlanType.PREMIUM_TRIAL
                    ? PlanWhatsAppMessages.trialReminder(expiry.date())
                    : PlanWhatsAppMessages.reminder(expiry.date());
            whatsAppPort.sendReport(phone.get(), text);
            log.info("Plan expiry reminder ({} d) sent for tenant {}", threshold, tenantId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Plan expiry reminder ({} d) failed for tenant {} — retried next run: {}: {}",
                    threshold, tenantId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}

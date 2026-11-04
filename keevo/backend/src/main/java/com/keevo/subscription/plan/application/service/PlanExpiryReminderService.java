package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.domain.time.KeevoZones;
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

    /** Story 19.7a: tenants on a plan that expires (kind TRIAL or PAID, joined); parameters: the two kinds. */
    static final String FETCH_TENANTS =
            "SELECT t.id, t.schema_name FROM public.tenants t JOIN public.plans p ON p.code = t.plan_type "
            + "WHERE t.status NOT IN ('DELETED', 'DELETION_PENDING') AND p.kind IN (?, ?) "
            + "AND t.id <> '00000000-0000-0000-0000-000000000000'::uuid";

    /** Story 19.7a: the subscription of a plan that expires, with its kind; parameters: the two kinds. */
    static final String EXPIRY_SQL =
            "SELECT p.kind, p.label, s.expires_at FROM \"%s\".subscriptions s JOIN public.plans p ON p.code = s.plan_type "
            + "WHERE p.kind IN (?, ?) AND s.status = 'ACTIVE' AND s.expires_at IS NOT NULL "
            + "ORDER BY s.created_at ASC LIMIT 1";

    static final String CLAIM_SQL =
            "INSERT INTO public.plan_expiry_reminders (tenant_id, threshold_days, expiry_date) "
            + "VALUES (?, ?, ?) ON CONFLICT (tenant_id, threshold_days, expiry_date) DO NOTHING";

    static final String UNCLAIM_SQL =
            "DELETE FROM public.plan_expiry_reminders "
            + "WHERE tenant_id = ? AND threshold_days = ? AND expiry_date = ?";

    private record Expiry(PlanKind kind, String label, LocalDate date) {}

    private final JdbcTemplate jdbc;
    private final WhatsAppSender whatsAppSender;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final Clock clock;

    public PlanExpiryReminderService(JdbcTemplate jdbc, WhatsAppSender whatsAppSender,
                                     OwnerRecipientResolver ownerRecipientResolver, Clock clock) {
        this.jdbc = jdbc;
        this.whatsAppSender = whatsAppSender;
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
        LocalDate today = LocalDate.now(clock.withZone(KeevoZones.WAT));
        int sent = 0;
        for (Map<String, Object> tenant : jdbc.queryForList(FETCH_TENANTS, PlanKind.TRIAL.name(),
                PlanKind.PAID.name())) {
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
        if (!TenantSchema.isValid(schema)) { // run loop filters; re-checked before expiry() builds SQL
            log.warn("Plan expiry reminder: tenant {} skipped (invalid schema name)", tenantId);
            return false;
        }
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
        List<Map<String, Object>> rows = jdbc.queryForList(String.format(EXPIRY_SQL, schema),
                PlanKind.TRIAL.name(), PlanKind.PAID.name());
        if (rows.isEmpty() || rows.get(0).get("expires_at") == null) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        Object exp = row.get("expires_at");
        java.time.Instant instant = exp instanceof Timestamp ts ? ts.toInstant()
                : exp instanceof java.time.OffsetDateTime odt ? odt.toInstant() : (java.time.Instant) exp;
        return Optional.of(new Expiry(PlanKind.valueOf((String) row.get("kind")), (String) row.get("label"),
                instant.atZone(KeevoZones.WAT).toLocalDate()));
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
            String text = expiry.kind() == PlanKind.TRIAL
                    ? PlanWhatsAppMessages.trialReminder(expiry.date())
                    : PlanWhatsAppMessages.reminder(expiry.label(), expiry.date()); // 19.7b: plan label
            whatsAppSender.sendReport(phone.get(), text);
            log.info("Plan expiry reminder ({} d) sent for tenant {}", threshold, tenantId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Plan expiry reminder ({} d) failed for tenant {} — retried next run: {}: {}",
                    threshold, tenantId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}

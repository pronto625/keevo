package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.application.service.TargetTenantLookup.TargetTenant;
import com.keevo.admin.subscription.domain.model.PlanAlert;
import com.keevo.admin.subscription.domain.model.TenantPayment;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumCommand;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumUseCase;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeCommand;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeUseCase;
import com.keevo.admin.subscription.domain.port.in.ListPlanAlertsUseCase;
import com.keevo.admin.subscription.domain.port.in.ListTenantPaymentsUseCase;
import com.keevo.admin.subscription.domain.port.in.VoidTenantPaymentCommand;
import com.keevo.admin.subscription.domain.port.in.VoidTenantPaymentUseCase;
import com.keevo.admin.subscription.domain.port.out.TenantPaymentRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import static com.keevo.shared.domain.time.KeevoZones.WAT;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.plan.PlanDowngradedEvent;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.api.CurrentPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AdminPlanService — super-admin plan management (Story 9-2, FR78/FR81).
 *
 * <ul>
 *   <li><b>Activate Premium</b>: one transaction inserts the payment, sets the tenant-schema
 *       subscription to PREMIUM/ACTIVE until the end of the chosen day (WAT) with Premium limits,
 *       syncs {@code public.tenants.plan_type} and appends a plan event; a renewal starts the
 *       covered period at the current Premium expiry when it is later than today. The owner
 *       WhatsApp goes out after commit ({@link PlanActivatedEvent}).</li>
 *   <li><b>Downgrade to FREE</b>: same unit of work, FREE limits, no expiry ({@link PlanDowngradedEvent}).</li>
 *   <li><b>Payments</b>: history newest first; a payment entered by mistake is voided (kept, excluded
 *       from every count, including the renewal « current end »). <b>Alerts</b>: Premium/Trial expiring within 7 WAT
 *       days, and expiry downgrades of the last 7 days.</li>
 * </ul>
 *
 * <p>Audit trace: an INFO log line and a domain event carrying the actor id for every action.
 */
@Service
public class AdminPlanService implements ActivatePremiumUseCase, DowngradeToFreeUseCase,
        ListTenantPaymentsUseCase, ListPlanAlertsUseCase, VoidTenantPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminPlanService.class);

    static final int NOTE_MAX = 500;
    static final int ALERT_WINDOW_DAYS = 7;
    static final int REASON_MIN = 3;
    /** Farthest Premium expiry accepted: a typo must not grant decades. */
    static final int MAX_EXPIRY_YEARS = 3;
    /** Tolerated clock skew for a payment time sent by the dashboard. */
    static final Duration PAID_AT_SKEW = Duration.ofMinutes(5);
    /** Oldest payment date accepted. */
    static final Duration PAID_AT_MAX_AGE = Duration.ofDays(366);
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Only non-FREE tenants (public plan_type is kept in step with the schema since 9-2). */
    static final String ALERT_TENANTS_SQL =
            "SELECT id, name, schema_name FROM public.tenants "
            + "WHERE status NOT IN ('DELETED', 'DELETION_PENDING') AND plan_type <> 'FREE' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";

    static final String EXPIRING_SQL =
            "SELECT plan_type, expires_at FROM \"%s\".subscriptions "
            + "WHERE plan_type IN ('PREMIUM', 'PREMIUM_TRIAL') AND status = 'ACTIVE' "
            + "AND expires_at IS NOT NULL AND expires_at >= ? AND expires_at < ? "
            + "ORDER BY created_at ASC LIMIT 1";

    static final String EXPIRED_SQL =
            "SELECT e.tenant_id, t.name, e.from_plan, e.occurred_at "
            + "FROM public.tenant_plan_events e JOIN public.tenants t ON t.id = e.tenant_id "
            + "WHERE e.cause = 'EXPIRY' AND e.occurred_at >= ? "
            + "AND t.status NOT IN ('DELETED', 'DELETION_PENDING') "
            + "AND NOT EXISTS (SELECT 1 FROM public.tenant_plan_events later "
            + "  WHERE later.tenant_id = e.tenant_id AND later.occurred_at > e.occurred_at) "
            + "ORDER BY e.occurred_at DESC";

    private final TargetTenantLookup tenantLookup;
    private final PlanAdministrationPort changeTenantPlan;
    private final TenantPaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminPlanService(TargetTenantLookup tenantLookup,
                            PlanAdministrationPort changeTenantPlan,
                            TenantPaymentRepository paymentRepository,
                            ApplicationEventPublisher eventPublisher,
                            JdbcTemplate jdbc,
                            Clock clock) {
        this.tenantLookup = tenantLookup;
        this.changeTenantPlan = changeTenantPlan;
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ── Activate Premium ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public CurrentPlan activatePremium(ActivatePremiumCommand cmd) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, WAT);
        validateActivation(cmd, today, now);

        TargetTenant tenant = tenantLookup.load(cmd.tenantId());
        requireNotDeleted(tenant);

        CurrentPlan current = changeTenantPlan.currentPlan(tenant.schemaName())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + tenant.id()));

        // Renewal: a still-running Premium is extended from the day after its current expiry
        // (the last covered day is never paid twice); the new expiry must be strictly later.
        // An expiry granted by a since-voided payment does not count (typo correction).
        LocalDate periodStart = today;
        if (current.planType() == PlanType.PREMIUM && current.expiresAt() != null) {
            LocalDate currentEnd = effectiveCurrentEnd(tenant.id(), LocalDate.ofInstant(current.expiresAt(), WAT));
            if (currentEnd != null && !currentEnd.isBefore(today)) {
                if (!cmd.expiresAt().isAfter(currentEnd)) {
                    throw validation("expiresAt", "La date d'expiration doit être postérieure à l'échéance actuelle ("
                            + FR_DATE.format(currentEnd) + ")");
                }
                periodStart = currentEnd.plusDays(1);
            }
        }

        Instant expiresAt = endOfDayWat(cmd.expiresAt());
        paymentRepository.insert(new TenantPayment(
                UUID.randomUUID(), tenant.id(), cmd.amountXaf(), cmd.method(),
                cmd.paidAt() != null ? cmd.paidAt() : now,
                periodStart, cmd.expiresAt(), blankToNull(cmd.note()), cmd.actorId()));

        CurrentPlan previous = changeTenantPlan.changePlan(new ChangePlanInput(
                tenant.id(), tenant.schemaName(), PlanType.PREMIUM, expiresAt,
                PlanChangeCause.ADMIN_ACTIVATION, cmd.actorId(), blankToNull(cmd.note())));

        log.info("[ADMIN AUDIT] Premium activated: actor={} tenant={} until={} amountXaf={} method={} previousPlan={}",
                cmd.actorId(), tenant.id(), cmd.expiresAt(), cmd.amountXaf(), cmd.method(), previous.planType());
        eventPublisher.publishEvent(new PlanActivatedEvent(cmd.actorId(), tenant.id(), tenant.schemaName(),
                previous.planType(), expiresAt, cmd.amountXaf(), now));
        return new CurrentPlan(PlanType.PREMIUM, expiresAt);
    }

    // ── Downgrade to FREE ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void downgradeToFree(DowngradeToFreeCommand cmd) {
        String reason = blankToNull(cmd.reason());
        if (reason != null && reason.length() > NOTE_MAX) {
            throw validation("reason", "500 caractères maximum");
        }
        TargetTenant tenant = tenantLookup.load(cmd.tenantId());
        requireNotDeleted(tenant);

        CurrentPlan current = changeTenantPlan.currentPlan(tenant.schemaName())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + tenant.id()));
        if (current.planType() == PlanType.FREE) {
            throw new DomainException(ErrorCode.INVALID_STATE, "Tenant already on FREE: " + tenant.id());
        }

        changeTenantPlan.changePlan(new ChangePlanInput(
                tenant.id(), tenant.schemaName(), PlanType.FREE, null,
                PlanChangeCause.ADMIN_DOWNGRADE, cmd.actorId(), reason));

        Instant now = clock.instant();
        log.info("[ADMIN AUDIT] Plan downgraded to FREE: actor={} tenant={} previousPlan={}",
                cmd.actorId(), tenant.id(), current.planType());
        eventPublisher.publishEvent(new PlanDowngradedEvent(cmd.actorId(), tenant.id(), tenant.schemaName(),
                current.planType(), reason, now));
    }

    /**
     * The subscription end, unless it was granted by a payment voided since and no counted payment
     * covers it: then the latest end among the counted payments (null when none).
     */
    private LocalDate effectiveCurrentEnd(UUID tenantId, LocalDate subscriptionEnd) {
        List<TenantPayment> payments = paymentRepository.findByTenant(tenantId);
        boolean grantedByVoided = payments.stream()
                .anyMatch(p -> p.isVoided() && p.periodEnd().equals(subscriptionEnd));
        boolean coveredByCounted = payments.stream()
                .anyMatch(p -> !p.isVoided() && !p.periodEnd().isBefore(subscriptionEnd));
        if (!grantedByVoided || coveredByCounted) return subscriptionEnd;
        return payments.stream().filter(p -> !p.isVoided()).map(TenantPayment::periodEnd)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    // ── Payments ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void voidPayment(VoidTenantPaymentCommand cmd) {
        String reason = cmd.reason() == null ? "" : cmd.reason().trim();
        if (reason.length() < REASON_MIN || reason.length() > NOTE_MAX) {
            throw validation("reason", "Motif requis (3 à 500 caractères)");
        }
        TargetTenant tenant = tenantLookup.load(cmd.tenantId());
        TenantPayment payment = paymentRepository.findById(tenant.id(), cmd.paymentId())
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Paiement introuvable"));
        if (payment.isVoided()
                || !paymentRepository.voidPayment(tenant.id(), payment.id(), cmd.actorId(), reason, clock.instant())) {
            throw new DomainException(ErrorCode.INVALID_STATE, "Payment already voided: " + payment.id());
        }
        log.info("[ADMIN AUDIT] Payment voided: actor={} tenant={} payment={} amountXaf={} periodEnd={} reason={}",
                cmd.actorId(), tenant.id(), payment.id(), payment.amountXaf(), payment.periodEnd(), reason);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantPayment> listPayments(UUID tenantId) {
        TargetTenant tenant = tenantLookup.load(tenantId);
        return paymentRepository.findByTenant(tenant.id());
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    /**
     * Not transactional on purpose: each per-tenant query autocommits, so one broken tenant schema
     * cannot abort the PostgreSQL transaction of the following queries.
     */
    @Override
    public List<PlanAlert> listAlerts() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, WAT);
        // expiry within 7 WAT calendar days: before the start of day today+8
        Timestamp horizon = Timestamp.from(today.plusDays(ALERT_WINDOW_DAYS + 1L).atStartOfDay(WAT).toInstant());

        List<PlanAlert> expiring = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList(ALERT_TENANTS_SQL)) {
            String schema = (String) t.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue;
            UUID id = toUuid(t.get("id"));
            Map<String, Object> row = expiringRow(id, schema, Timestamp.from(now), horizon);
            if (row == null) continue;
            Instant expiresAt = toInstant(row.get("expires_at"));
            int daysLeft = (int) ChronoUnit.DAYS.between(today, LocalDate.ofInstant(expiresAt, WAT));
            expiring.add(new PlanAlert(id, (String) t.get("name"), PlanAlert.Kind.EXPIRING,
                    (String) row.get("plan_type"), Math.max(0, daysLeft), expiresAt, null));
        }
        expiring.sort(Comparator.comparing(PlanAlert::daysLeft));

        List<PlanAlert> expired = jdbc.query(EXPIRED_SQL, (rs, i) -> new PlanAlert(
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                PlanAlert.Kind.EXPIRED,
                rs.getString("from_plan"),
                null, null,
                rs.getTimestamp("occurred_at").toInstant()),
                Timestamp.from(now.minus(ALERT_WINDOW_DAYS, ChronoUnit.DAYS)));

        List<PlanAlert> all = new ArrayList<>(expiring);
        all.addAll(expired);
        return all;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Last second of {@code day} in WAT (stored precision is microseconds: never round to the next day). */
    public static Instant endOfDayWat(LocalDate day) {
        return day.atTime(LocalTime.of(23, 59, 59)).atZone(WAT).toInstant();
    }

    private void validateActivation(ActivatePremiumCommand cmd, LocalDate today, Instant now) {
        if (cmd.expiresAt() == null) throw validation("expiresAt", "Date d'expiration requise");
        if (!cmd.expiresAt().isAfter(today)) {
            throw validation("expiresAt", "La date d'expiration doit être postérieure à aujourd'hui");
        }
        if (cmd.expiresAt().isAfter(today.plusYears(MAX_EXPIRY_YEARS))) {
            throw validation("expiresAt", "Date d'expiration trop lointaine");
        }
        if (cmd.amountXaf() <= 0) {
            throw validation("amountXaf", "Le montant doit être supérieur à 0");
        }
        if (cmd.amountXaf() > Integer.MAX_VALUE) {
            throw validation("amountXaf", "Montant trop élevé");
        }
        if (cmd.method() == null) throw validation("method", "Moyen de paiement requis");
        if (cmd.paidAt() != null && cmd.paidAt().isAfter(now.plus(PAID_AT_SKEW))) {
            throw validation("paidAt", "La date de paiement ne peut pas être dans le futur");
        }
        if (cmd.paidAt() != null && cmd.paidAt().isBefore(now.minus(PAID_AT_MAX_AGE))) {
            throw validation("paidAt", "Date de paiement trop ancienne");
        }
        if (cmd.note() != null && cmd.note().length() > NOTE_MAX) {
            throw validation("note", "500 caractères maximum");
        }
    }

    /** Plan changes are refused on a DELETED or DELETION_PENDING tenant (409, same as suspend). */
    private static void requireNotDeleted(TargetTenant tenant) {
        if (tenant.isDeleted() || "DELETION_PENDING".equals(tenant.status())) {
            throw new DomainException(ErrorCode.INVALID_STATE, "Tenant deleted or deletion pending: " + tenant.id());
        }
    }

    static DomainException validation(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }

    private Map<String, Object> expiringRow(UUID tenantId, String schema, Timestamp now, Timestamp horizon) {
        if (!TenantSchema.isValid(schema)) { // listAlerts() filters; re-checked at use
            log.warn("Plan alerts: tenant {} skipped (invalid schema name)", tenantId);
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(String.format(EXPIRING_SQL, schema), now, horizon);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            throw e; // outage / pool exhaustion: fail the request, never answer an empty list
        } catch (DataAccessException e) {
            log.warn("Plan alerts: tenant {} skipped ({})", tenantId, e.getClass().getSimpleName());
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        throw new IllegalStateException("Unexpected expires_at type: " + value);
    }
}

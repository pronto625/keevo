package com.keevo.admin.alerts.application.service;

import com.keevo.admin.alerts.domain.model.AdminAlert;
import com.keevo.admin.alerts.domain.model.AlertSeverity;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.in.CountAdminAlertsUseCase;
import com.keevo.admin.alerts.domain.port.in.HandleAdminAlertUseCase;
import com.keevo.admin.alerts.domain.port.in.ListAdminAlertsUseCase;
import com.keevo.admin.alerts.domain.port.in.PurgeAlertAcksUseCase;
import com.keevo.admin.alerts.domain.port.out.AlertAckRepository;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.admin.platform.domain.model.SystemHealth;
import com.keevo.admin.platform.domain.port.in.GetSystemHealthUseCase;
import com.keevo.admin.subscription.domain.model.PlanAlert;
import com.keevo.admin.subscription.domain.port.in.ListPlanAlertsUseCase;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.identity.api.AccountDeletionPolicy;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * AdminAlertService — platform alerts feed (Story 9-5), computed on each call from:
 *
 * <ul>
 *   <li>HIGH-priority feedback of the last 30 days not handled in the feedback inbox — HIGH;</li>
 *   <li>companies in DELETION_PENDING, with the days left — MEDIUM;</li>
 *   <li>subscriptions expired but still not FREE (integrity) — HIGH;</li>
 *   <li>Premium / trial expiring within 7 days (9-2 plan alerts) — LOW;</li>
 *   <li>more than 10 WhatsApp report delivery failures in the last hour — MEDIUM;</li>
 *   <li>companies whose devices have all been silent for more than 5 days — MEDIUM;</li>
 *   <li>each health metric over its threshold right now — HIGH.</li>
 * </ul>
 *
 * <p>Each alert has a deterministic key; « Marquer comme traité » stores the key in
 * {@code admin_alert_acks} (and, for a feedback, marks it handled in the 9-1 inbox too). Keys embed
 * what makes a new occurrence different (expiry date, last push). Ongoing conditions (health metrics,
 * WhatsApp failures) have a key without time: once handled they stay hidden while the condition lasts,
 * and their ack is deleted when the condition clears so a future occurrence alerts again.
 *
 * <p>Outages: each source runs on its own — a database error leaves that source empty (logged) and
 * unreadable acks count as « none handled », so the health alerts (« Base de données injoignable »)
 * are always returned.
 *
 * <p>The computed feed is cached 30 s (single-flight: one computation at a time, concurrent callers
 * reuse it) and invalidated by « Marquer comme traité ». Not {@code @Transactional}: the
 * cross-company reads autocommit one by one.
 */
@Service
public class AdminAlertService implements ListAdminAlertsUseCase, HandleAdminAlertUseCase,
        CountAdminAlertsUseCase, PurgeAlertAcksUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final Duration FEEDBACK_WINDOW = Duration.ofDays(30);
    static final Duration REPORT_FAILURE_WINDOW = Duration.ofHours(1);
    static final long REPORT_FAILURE_THRESHOLD = 10;
    public static final Duration SYNC_SILENCE = Duration.ofDays(5);
    /** One missed nightly downgrade run before an expired plan is an integrity issue. */
    static final Duration EXPIRY_TOLERANCE = Duration.ofHours(26);
    static final Duration FEED_TTL = Duration.ofSeconds(30);
    static final Duration ACK_RETENTION = Duration.ofDays(90);
    static final String WHATSAPP_FAILURES_KEY = "whatsapp-failures";
    static final int FEEDBACK_EXCERPT_MAX = 160;
    static final int KEY_MAX = 200;
    static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*(:[A-Za-z0-9-]+)*$");

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AlertSourcesPort sources;
    private final AlertAckRepository acks;
    private final ListPlanAlertsUseCase planAlerts;
    private final GetSystemHealthUseCase health;
    private final HandleFeedbackUseCase handleFeedback;
    private final Clock clock;

    private final ReentrantLock feedLock = new ReentrantLock();
    private record CachedFeed(Instant computedAt, List<AdminAlert> alerts) {}
    private volatile CachedFeed cached;

    public AdminAlertService(AlertSourcesPort sources, AlertAckRepository acks,
                             ListPlanAlertsUseCase planAlerts, GetSystemHealthUseCase health,
                             HandleFeedbackUseCase handleFeedback, Clock clock) {
        this.sources = sources;
        this.acks = acks;
        this.planAlerts = planAlerts;
        this.health = health;
        this.handleFeedback = handleFeedback;
        this.clock = clock;
    }

    // ── Feed ──────────────────────────────────────────────────────────────────

    @Override
    public List<AdminAlert> listAlerts(boolean includeHandled) {
        return feed().stream().filter(a -> includeHandled || !a.handled()).toList();
    }

    @Override
    public long countUnhandled(AlertSeverity severity) {
        return feed().stream()
                .filter(a -> !a.handled() && (severity == null || a.severity() == severity))
                .count();
    }

    @Override
    public void markHandled(String alertKey, UUID actorId) {
        if (alertKey == null || alertKey.length() > KEY_MAX || !KEY_PATTERN.matcher(alertKey).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Alerte inconnue",
                    Map.of("key", "Alerte inconnue"));
        }
        if (alertKey.startsWith("feedback:")) markFeedbackHandled(alertKey.substring("feedback:".length()), actorId);
        acks.markHandled(alertKey, clock.instant(), actorId);
        cached = null;
    }

    @Override
    public int purgeOldAcks() {
        return acks.purgeBefore(clock.instant().minus(ACK_RETENTION));
    }

    /** Same « traité » state as the « Retours utilisateurs » inbox; a feedback gone is just acked. */
    private void markFeedbackHandled(String rawId, UUID actorId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return;
        }
        try {
            handleFeedback.markHandled(id, actorId);
        } catch (DomainException e) {
            log.warn("Alerts: feedback of a handled alert not found ({})", e.getErrorCode());
        }
    }

    // ── Feed computation ──────────────────────────────────────────────────────

    /** Every alert with its handled state, recomputed at most every 30 s (single-flight). */
    private List<AdminAlert> feed() {
        CachedFeed c = cached;
        if (fresh(c)) return c.alerts();
        feedLock.lock();
        try {
            c = cached;
            if (fresh(c)) return c.alerts();
            List<AdminAlert> computed = compute();
            cached = new CachedFeed(clock.instant(), computed);
            return computed;
        } finally {
            feedLock.unlock();
        }
    }

    private boolean fresh(CachedFeed c) {
        return c != null && c.computedAt().plus(FEED_TTL).isAfter(clock.instant());
    }

    private List<AdminAlert> compute() {
        Instant now = clock.instant();
        List<AdminAlert> all = new ArrayList<>();
        /** Ongoing-condition keys whose source was read: absent ones are cleared conditions. */
        Set<String> checkedConditions = new HashSet<>();

        SystemHealth current = health.recentHealth();
        for (SystemHealth.Metric m : SystemHealth.Metric.values()) checkedConditions.add(healthKey(m));
        current.breachedMetrics().forEach(m -> all.add(healthAlert(m, current)));

        safely("feedback", () -> sources.unhandledHighFeedbackSince(now.minus(FEEDBACK_WINDOW)), List.<HighFeedback>of())
                .forEach(f -> all.add(feedbackAlert(f)));
        safely("expired plans", () -> sources.expiredPlansNotFree(now.minus(EXPIRY_TOLERANCE)), List.<ExpiredPlan>of())
                .forEach(p -> all.add(expiredPlanAlert(p)));
        safely("deletions", sources::deletionPending, List.<DeletionPending>of())
                .forEach(d -> all.add(deletionAlert(d, now)));
        ReportFailures failures = safely("whatsapp failures",
                () -> sources.reportFailuresSince(now.minus(REPORT_FAILURE_WINDOW)), null);
        if (failures != null) {
            checkedConditions.add(WHATSAPP_FAILURES_KEY);
            if (failures.failures() > REPORT_FAILURE_THRESHOLD) all.add(reportFailuresAlert(failures, now));
        }
        safely("silent companies", () -> sources.silentTenants(now.minus(SYNC_SILENCE)), List.<SilentTenant>of())
                .forEach(s -> all.add(silenceAlert(s, now)));
        safely("plan alerts", planAlerts::listAlerts, List.<PlanAlert>of()).stream()
                .filter(a -> a.kind() == PlanAlert.Kind.EXPIRING)
                .forEach(a -> all.add(expiringPlanAlert(a, now)));

        Set<String> keys = new HashSet<>();
        all.forEach(a -> keys.add(a.key()));
        Set<String> lookup = new HashSet<>(keys);
        lookup.addAll(checkedConditions);
        Map<String, Instant> handled = safely("acks", () -> acks.handledAt(lookup), Map.<String, Instant>of());

        List<String> cleared = checkedConditions.stream()
                .filter(k -> !keys.contains(k) && handled.containsKey(k))
                .toList();
        if (!cleared.isEmpty()) safely("ack cleanup", () -> acks.delete(cleared), 0);

        return all.stream()
                .map(a -> a.withHandledAt(handled.get(a.key())))
                .sorted(Comparator.comparing(AdminAlert::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(a -> a.severity().ordinal())
                        .thenComparing(AdminAlert::key))
                .toList();
    }

    /** One source: a database error leaves it empty (logged), never fails the whole feed. */
    private <T> T safely(String source, Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.warn("Alerts: source « {} » unavailable ({})", source, e.getClass().getSimpleName());
            return fallback;
        }
    }

    // ── Alert builders ────────────────────────────────────────────────────────

    AdminAlert feedbackAlert(HighFeedback f) {
        String excerpt = excerpt(f.description());
        String text = f.tenantName() + " — " + feedbackTypeLabel(f.type())
                + (excerpt.isEmpty() ? "" : " : « " + excerpt + " »");
        return alert("feedback:" + f.id(), AlertSeverity.HIGH, "Retour utilisateur prioritaire", text, f.submittedAt());
    }

    AdminAlert deletionAlert(DeletionPending d, Instant now) {
        Instant scheduled = d.deletionScheduledAt();
        String text;
        String keyPart;
        Instant occurredAt;
        if (scheduled == null) {
            text = d.tenantName() + " — suppression du compte demandée";
            keyPart = "pending";
            occurredAt = now;
        } else {
            long hoursLeft = Math.max(0, Duration.between(now, scheduled).toHours());
            long daysLeft = (hoursLeft + 23) / 24;
            text = d.tenantName() + (daysLeft == 0
                    ? " — suppression définitive imminente"
                    : " — suppression définitive dans " + days(daysLeft) + " (le "
                      + FR_DATE.format(scheduled.atZone(WAT)) + ")");
            keyPart = DAY_KEY.format(scheduled.atZone(WAT));
            occurredAt = scheduled.minus(AccountDeletionPolicy.GRACE_PERIOD);
        }
        return alert("deletion:" + d.tenantId() + ":" + keyPart, AlertSeverity.MEDIUM,
                "Suppression de compte demandée", text, occurredAt);
    }

    AdminAlert expiredPlanAlert(ExpiredPlan p) {
        LocalDate day = p.expiresAt().atZone(WAT).toLocalDate();
        String text = p.tenantName() + " — abonnement " + planLabel(p.plan()) + " expiré le "
                + FR_DATE.format(day) + " mais pas encore repassé au plan Gratuit";
        return alert("plan-expired:" + p.tenantId() + ":" + DAY_KEY.format(day), AlertSeverity.HIGH,
                "Abonnement expiré non rétrogradé", text, p.expiresAt());
    }

    AdminAlert expiringPlanAlert(PlanAlert a, Instant now) {
        LocalDate day = a.expiresAt().atZone(WAT).toLocalDate();
        int daysLeft = a.daysLeft() == null ? 0 : a.daysLeft();
        String when = daysLeft == 0 ? "aujourd'hui" : "dans " + days(daysLeft);
        String text = a.tenantName() + " — abonnement " + planLabel(a.plan()) + " expire " + when
                + " (le " + FR_DATE.format(day) + ")";
        return alert("plan-expiring:" + a.tenantId() + ":" + DAY_KEY.format(day), AlertSeverity.LOW,
                "Abonnement bientôt expiré", text, min(now, a.expiresAt().minus(Duration.ofDays(7))));
    }

    AdminAlert reportFailuresAlert(ReportFailures f, Instant now) {
        String text = f.failures() + " rapports WhatsApp n'ont pas pu être envoyés au cours de la dernière heure ("
                + f.companies() + (f.companies() > 1 ? " entreprises concernées)" : " entreprise concernée)");
        return alert(WHATSAPP_FAILURES_KEY, AlertSeverity.MEDIUM,
                "Échecs d'envoi WhatsApp", text, f.latestAt() != null ? f.latestAt() : now);
    }

    AdminAlert silenceAlert(SilentTenant s, Instant now) {
        long silentDays = ChronoUnit.DAYS.between(s.lastPushAt().atZone(WAT).toLocalDate(), now.atZone(WAT).toLocalDate());
        String text = s.tenantName() + " — aucune synchronisation depuis " + days(silentDays)
                + " (dernière le " + FR_DATE.format(s.lastPushAt().atZone(WAT)) + ")";
        return alert("sync-silence:" + s.tenantId() + ":" + s.lastPushAt().getEpochSecond(), AlertSeverity.MEDIUM,
                "Synchronisation interrompue", text, s.lastPushAt().plus(SYNC_SILENCE));
    }

    AdminAlert healthAlert(SystemHealth.Metric metric, SystemHealth h) {
        String text = switch (metric) {
            case DATABASE -> "Base de données injoignable : le contrôle de connexion a échoué.";
            case ERROR_RATE -> "Taux d'erreurs dépasse le seuil : " + percent(h.errorRatePercent1h())
                    + " sur la dernière heure (seuil " + percent(SystemHealth.ERROR_RATE_THRESHOLD_PERCENT) + ").";
            case LATENCY -> "Temps de réponse (p99) dépasse le seuil : " + number(h.p99Ms()) + " ms (seuil "
                    + number(SystemHealth.P99_THRESHOLD_MS) + " ms).";
            case SYNC_REFUSALS -> "Refus de synchronisation dépassent le seuil : " + number(h.syncRefusals1h())
                    + " sur la dernière heure (seuil " + number(SystemHealth.SYNC_REFUSALS_THRESHOLD) + ").";
            case DB_CONNECTIONS -> "Connexions actives à la base : " + number(h.dbActiveConnections()) + " sur "
                    + number(h.dbMaxConnections()) + " (seuil 80 %).";
            case UPTIME -> "Disponibilité sur 30 jours sous le seuil : " + percent(h.uptimePercent30d())
                    + " (seuil " + percent(SystemHealth.UPTIME_THRESHOLD_PERCENT) + ").";
        };
        return alert(healthKey(metric), AlertSeverity.HIGH, "Incident système", text, h.checkedAt());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String healthKey(SystemHealth.Metric metric) {
        return "health:" + metric.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Trimmed, cut on code points (never inside a surrogate pair). */
    static String excerpt(String description) {
        String text = description == null ? "" : description.strip();
        if (text.codePointCount(0, text.length()) <= FEEDBACK_EXCERPT_MAX) return text;
        int end = text.offsetByCodePoints(0, FEEDBACK_EXCERPT_MAX - 1);
        return text.substring(0, end) + "…";
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static AdminAlert alert(String key, AlertSeverity severity, String title, String description,
                                    Instant occurredAt) {
        return new AdminAlert(key, severity, title, description, occurredAt, false, null);
    }

    /** Same labels as the dashboard ({@code feedbackTypeLabel} in {@code lib/labels.ts}). */
    static String feedbackTypeLabel(String type) {
        if (type == null) return "Autre";
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "BUG" -> "Problème signalé";
            case "SUGGESTION" -> "Suggestion";
            case "PRAISE" -> "Compliment";
            default -> "Autre";
        };
    }

    static String planLabel(String plan) {
        if (plan == null) return "payant";
        return switch (plan) {
            case "PREMIUM" -> "Premium";
            case "PREMIUM_TRIAL" -> "Essai Premium";
            case "FREE" -> "Gratuit";
            default -> "payant";
        };
    }

    static String days(long n) {
        return n + (n > 1 ? " jours" : " jour");
    }

    private static String percent(Double value) {
        if (value == null) return "—";
        DecimalFormat f = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.FRANCE));
        return f.format(value) + " %";
    }

    private static String number(Number value) {
        if (value == null) return "—";
        return String.format(Locale.FRANCE, "%,d", value.longValue()).replace(' ', ' ').replace(' ', ' ');
    }
}

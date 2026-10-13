package com.keevo.admin.notification.application.service;

import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import com.keevo.admin.notification.domain.port.in.CancelGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.EstimateGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.ListAnnouncementsUseCase;
import com.keevo.admin.notification.domain.port.in.ListGlobalNotificationsUseCase;
import com.keevo.admin.notification.domain.port.in.PurgeGlobalNotificationsUseCase;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationCommand;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.SearchPushTenantsUseCase;
import com.keevo.admin.notification.domain.port.out.GlobalNotificationRepository;
import com.keevo.admin.notification.domain.port.out.PushAudiencePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * GlobalNotificationService — compose, history, cancel, estimate and purge of the global pushes of
 * the super admin (Story 9-5, FR82). Sending is done by {@link GlobalNotificationDispatcher}.
 *
 * <p>Validation errors are 422 {@code VALIDATION_ERROR} with French field messages in {@code details}.
 */
@Service
public class GlobalNotificationService implements ScheduleGlobalNotificationUseCase, ListGlobalNotificationsUseCase,
        CancelGlobalNotificationUseCase, EstimateGlobalNotificationUseCase, PurgeGlobalNotificationsUseCase,
        SearchPushTenantsUseCase, ListAnnouncementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GlobalNotificationService.class);

    public static final int TITLE_MAX = 80;
    public static final int BODY_MAX = 200;
    public static final Duration HISTORY = Duration.ofDays(90);
    static final Duration PAST_TOLERANCE = Duration.ofMinutes(1);
    static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(30);

    private final GlobalNotificationRepository repository;
    private final PushAudiencePort audience;
    private final Clock clock;

    public GlobalNotificationService(GlobalNotificationRepository repository, PushAudiencePort audience, Clock clock) {
        this.repository = repository;
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public Scheduled schedule(ScheduleGlobalNotificationCommand cmd) {
        Instant now = clock.instant();
        Map<String, Object> errors = new LinkedHashMap<>();
        String title = trimmed(cmd.title());
        String body = trimmed(cmd.body());
        if (title.isEmpty()) errors.put("title", "Le titre est obligatoire");
        else if (title.length() > TITLE_MAX) errors.put("title", "80 caractères maximum");
        if (body.isEmpty()) errors.put("body", "Le message est obligatoire");
        else if (body.length() > BODY_MAX) errors.put("body", "200 caractères maximum");
        Audience target = parseAudience(cmd.target(), cmd.targetTenantId(), errors);
        Instant scheduledAt = parseScheduledAt(cmd.scheduledAt(), now, errors);
        if (!errors.isEmpty()) throw invalid(errors);

        // Estimate first: when it fails nothing is stored, so a retry cannot create a duplicate push.
        long estimate;
        try {
            estimate = audience.countDeviceTokens(audience.targetTenants(target.target(), target.tenantId()));
        } catch (DataAccessException e) {
            log.warn("Global push not scheduled: recipient estimate failed ({})", e.getClass().getSimpleName());
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Recipient estimate unavailable");
        }
        UUID id = UUID.randomUUID();
        repository.insert(new GlobalNotification(id, title, body, target.target(), target.tenantId(), null,
                scheduledAt, GlobalNotificationStatus.SCHEDULED, null, null, null, null, cmd.actorId(), now, null));
        log.info("[ADMIN AUDIT] global push scheduled — actor={} id={} target={} targetTenant={} scheduledAt={} "
                        + "estimate={}", cmd.actorId(), id, target.target(), target.tenantId(), scheduledAt, estimate);
        return new Scheduled(id, estimate);
    }

    @Override
    public List<GlobalNotification> history() {
        return repository.findCreatedSince(clock.instant().minus(HISTORY));
    }

    @Override
    public void cancel(UUID id, UUID actorId) {
        if (repository.cancelIfScheduled(id, clock.instant())) {
            log.info("[ADMIN AUDIT] global push cancelled — actor={} id={}", actorId, id);
            return;
        }
        if (repository.findById(id).isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Annonce introuvable");
        }
        throw new DomainException(ErrorCode.INVALID_STATE, "Seule une annonce programmée peut être annulée");
    }

    @Override
    public long estimate(String target, String targetTenantId) {
        Map<String, Object> errors = new LinkedHashMap<>();
        Audience a = parseAudience(target, targetTenantId, errors);
        if (!errors.isEmpty()) throw invalid(errors);
        return audience.countDeviceTokens(audience.targetTenants(a.target(), a.tenantId()));
    }

    @Override
    public int purgeOld() {
        int purged = repository.purgeCreatedBefore(clock.instant().minus(HISTORY));
        if (purged > 0) log.info("Global push history: {} row(s) older than 90 days purged", purged);
        return purged;
    }

    @Override
    public List<PushTenantRef> searchTenants(String search) {
        return audience.searchReachable(search == null ? "" : search.strip(), LIMIT);
    }

    @Override
    public List<Announcement> announcements(String tenantSchema, String since) {
        Instant floor = clock.instant().minus(HISTORY);
        Instant from = floor;
        if (since != null && !since.isBlank()) {
            try {
                Instant parsed = OffsetDateTime.parse(since.strip()).toInstant();
                if (parsed.isAfter(floor)) from = parsed;
            } catch (DateTimeParseException e) {
                throw invalid(Map.of("since", "Date invalide"));
            }
        }
        Instant start = from;
        return audience.tenantOfSchema(tenantSchema)
                .map(t -> repository.findSentFor(t, start))
                .orElse(List.of());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private record Audience(GlobalNotificationTarget target, UUID tenantId) {}

    private Audience parseAudience(String rawTarget, String rawTenantId, Map<String, Object> errors) {
        GlobalNotificationTarget target = null;
        if (rawTarget == null || rawTarget.isBlank()) {
            errors.put("target", "La cible est obligatoire");
        } else {
            try {
                target = GlobalNotificationTarget.valueOf(rawTarget.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("target", "Cible inconnue");
            }
        }
        boolean hasTenant = rawTenantId != null && !rawTenantId.isBlank();
        if (target != GlobalNotificationTarget.TENANT) {
            if (hasTenant && target != null) errors.put("targetTenantId", "Entreprise permise seulement pour « Un commerce »");
            return new Audience(target, null);
        }
        if (!hasTenant) {
            errors.put("targetTenantId", "Choisissez l'entreprise");
            return new Audience(target, null);
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(rawTenantId.strip());
        } catch (IllegalArgumentException e) {
            errors.put("targetTenantId", "Entreprise introuvable");
            return new Audience(target, null);
        }
        if (audience.reachableTenant(tenantId).map(PushTargetTenant::id).isEmpty()) {
            errors.put("targetTenantId", "Entreprise introuvable, supprimée ou suspendue");
        }
        return new Audience(target, tenantId);
    }

    private static Instant parseScheduledAt(String raw, Instant now, Map<String, Object> errors) {
        if (raw == null || raw.isBlank()) return now;
        Instant at;
        try {
            at = OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException e) {
            errors.put("scheduledAt", "Date d'envoi invalide");
            return now;
        }
        if (at.isBefore(now.minus(PAST_TOLERANCE))) {
            errors.put("scheduledAt", "La date d'envoi est déjà passée");
        } else if (at.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            errors.put("scheduledAt", "30 jours maximum à l'avance");
        }
        return at;
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.strip(); // Unicode spaces too, same as the dashboard's JS trim()
    }

    private static DomainException invalid(Map<String, Object> errors) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid global notification", errors);
    }
}

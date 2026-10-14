package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.PreferenceUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.ReportPreferencesUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesCommand;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * UpdateReportPreferencesService — Applies validated report preferences to the current tenant.
 *
 * <p>Publishes a {@link PreferenceUpdatedEvent} so the auto-scheduler can adapt
 * its per-tenant trigger time without a circular dependency.
 */
@Service
@Transactional
public class UpdateReportPreferencesService implements UpdateReportPreferencesUseCase {

    private final TenantPreferencesRepository preferencesRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateReportPreferencesService(TenantPreferencesRepository preferencesRepository,
                                          ApplicationEventPublisher eventPublisher) {
        this.preferencesRepository = preferencesRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TenantPreferences update(UpdateReportPreferencesCommand command) {
        TenantPreferences existing = preferencesRepository.findByCurrentTenant()
                .orElseThrow(() -> new DomainException(ErrorCode.PREFERENCES_NOT_FOUND));

        String newEodTime = command.eodReportTime() != null
                ? command.eodReportTime()
                : existing.eodReportTime();

        boolean newTrendNotificationEnabled = command.trendNotificationEnabled() != null
                ? command.trendNotificationEnabled()
                : existing.trendNotificationEnabled();

        TenantPreferences updated = new TenantPreferences(
            existing.id(),
            existing.sectorType(),
            newEodTime,
            command.stockAlertEnabled(),
            existing.createdAt(),
            command.eodReportEnabled(),
            command.eodReportChannel(),
            command.weeklyReportEnabled(),
            command.weeklyReportDay(),
            command.weeklyReportTime(),
            command.weeklyReportChannel(),
            command.inventoryReportEnabled(),
            command.inventoryReportChannel(),
            command.stockAlertChannel(),
            newTrendNotificationEnabled
        );

        TenantPreferences saved = preferencesRepository.update(updated);

        eventPublisher.publishEvent(
                new PreferenceUpdatedEvent(existing.id().toString(), newEodTime));

        // Action history: only the preferences that really changed, in plain French values.
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff("eodReportEnabled", existing.eodReportEnabled(), updated.eodReportEnabled(), before, after);
        diff("eodReportTime", time(existing.eodReportTime()), time(updated.eodReportTime()), before, after);
        diff("eodReportChannel", channel(existing.eodReportChannel()), channel(updated.eodReportChannel()),
                before, after);
        diff("weeklyReportEnabled", existing.weeklyReportEnabled(), updated.weeklyReportEnabled(), before, after);
        diff("weeklyReportDay", day(existing.weeklyReportDay()), day(updated.weeklyReportDay()), before, after);
        diff("weeklyReportTime", time(existing.weeklyReportTime()), time(updated.weeklyReportTime()),
                before, after);
        diff("weeklyReportChannel", channel(existing.weeklyReportChannel()),
                channel(updated.weeklyReportChannel()), before, after);
        diff("inventoryReportEnabled", existing.inventoryReportEnabled(), updated.inventoryReportEnabled(),
                before, after);
        diff("inventoryReportChannel", channel(existing.inventoryReportChannel()),
                channel(updated.inventoryReportChannel()), before, after);
        diff("stockAlertEnabled", existing.stockAlertEnabled(), updated.stockAlertEnabled(), before, after);
        diff("stockAlertChannel", alertChannel(existing.stockAlertChannel()),
                alertChannel(updated.stockAlertChannel()), before, after);
        diff("trendNotificationEnabled", existing.trendNotificationEnabled(),
                updated.trendNotificationEnabled(), before, after);
        if (!after.isEmpty()) {
            eventPublisher.publishEvent(new ReportPreferencesUpdatedEvent(
                    existing.id(), before, after, currentActorOrNull(),
                    TenantContext.getCurrentTenant(), Instant.now()));
        }

        return saved;
    }

    private static void diff(String key, Object oldValue, Object newValue,
                             Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(oldValue, newValue)) {
            before.put(key, oldValue);
            after.put(key, newValue);
        }
    }

    /** "20:00:00" → "20:00". */
    private static String time(String hhmmss) {
        if (hhmmss == null) return null;
        return hhmmss.length() >= 5 ? hhmmss.substring(0, 5) : hhmmss;
    }

    private static String day(int day) {
        return switch (day) {
            case 0 -> "Dimanche";
            case 1 -> "Lundi";
            case 2 -> "Mardi";
            case 3 -> "Mercredi";
            case 4 -> "Jeudi";
            case 5 -> "Vendredi";
            case 6 -> "Samedi";
            default -> null;
        };
    }

    private static String channel(ReportChannel channel) {
        if (channel == null) return null;
        return switch (channel) {
            case WHATSAPP -> "WhatsApp";
            case IN_APP_ONLY -> "Dans l'application";
        };
    }

    private static String alertChannel(StockAlertChannel channel) {
        if (channel == null) return null;
        return switch (channel) {
            case PUSH -> "Notification";
            case WHATSAPP -> "WhatsApp";
            case BOTH -> "Notification et WhatsApp";
        };
    }

    private static UUID currentActorOrNull() {
        try {
            return CurrentActor.actorId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

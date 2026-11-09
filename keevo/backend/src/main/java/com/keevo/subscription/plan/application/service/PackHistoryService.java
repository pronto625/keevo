package com.keevo.subscription.plan.application.service;

import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsagePackSource;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.export.KeysetCursor;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore.PackMovementRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.3 — the pack journal of a company as « Mon plan » shows it (routed from the 19.8 review): every pack bought
 * and every unit consumed from a pack — who, when, which counter. Read model over
 * {@code public.usage_credit_movements}; keyset pagination (opaque cursor on {@code created_at, id}), one query per
 * page with the actor names joined (no N+1), French labels only (no id, no code to show).
 */
@Service
public class PackHistoryService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final String METRIC_INVALID = "Compteur inconnu";
    static final String CURSOR_INVALID = "Page demandée invalide : rechargez l'historique";
    static final String LIMIT_INVALID = "Le nombre de lignes doit être compris entre 1 et 100";
    static final String AUTOMATIC = "Envoi automatique";
    static final String SOMEONE = "Un membre de l'équipe";
    static final String OWNER = "Le propriétaire";
    private static final List<KeyType> CURSOR = List.of(KeyType.TIMESTAMP, KeyType.UUID);

    /**
     * One movement.
     *
     * @param metric       {@code DOCUMENTS | SMS | EMAILS | AI_CREDITS} (app logic)
     * @param change       units added (+) or consumed (−)
     * @param source       {@code PURCHASE | CONSUMPTION | MIGRATION} (app logic)
     * @param counterLabel which quota was used up when the pack paid (« Quota du mois épuisé »), {@code null} otherwise
     * @param byLabel      who (first and last name, « Envoi automatique »), {@code null} for an import
     */
    public record PackMovementView(String metric, String metricLabel, int change, long remainingAfter, String source,
                                   String sourceLabel, String counterLabel, String byLabel, Instant at) {
    }

    /** @param nextCursor pass it as {@code cursor} for the next page, {@code null} on the last page */
    public record PackMovementsPage(List<PackMovementView> items, String nextCursor) {
    }

    private final UsageCounterStore store;

    public PackHistoryService(UsageCounterStore store) {
        this.store = store;
    }

    /**
     * @param metric optional metric code (case-insensitive)
     * @param cursor optional cursor of the previous page
     * @param limit  optional, 1..100 (default 20)
     */
    @Transactional(readOnly = true)
    public PackMovementsPage page(String tenantSchema, String metric, String cursor, Integer limit) {
        UsageMetric m = metric(metric);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("limit", LIMIT_INVALID);
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                List<Object> keys = KeysetCursor.decode(CURSOR, cursor.trim());
                beforeAt = (Instant) keys.get(0);
                beforeId = (UUID) keys.get(1);
            } catch (IllegalArgumentException | ClassCastException e) {
                throw invalid("cursor", CURSOR_INVALID);
            }
        }
        if (tenantSchema == null) {
            return new PackMovementsPage(List.of(), null);
        }
        // one row more than asked tells whether a next page exists
        List<PackMovementRow> rows = store.packMovements(tenantSchema, m, beforeAt, beforeId, size + 1);
        boolean more = rows.size() > size;
        List<PackMovementRow> page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            PackMovementRow last = page.get(page.size() - 1);
            next = KeysetCursor.encode(CURSOR, List.of(last.at(), last.id()));
        }
        return new PackMovementsPage(page.stream().map(PackHistoryService::view).toList(), next);
    }

    static PackMovementView view(PackMovementRow r) {
        return new PackMovementView(r.metric().name(), r.metric().label(), r.delta(), r.remainingAfter(),
                r.source().name(), r.source().label(), counterLabel(r), byLabel(r), r.at());
    }

    static String counterLabel(PackMovementRow r) {
        if (r.source() != UsagePackSource.CONSUMPTION || r.period() == null) {
            return null;
        }
        return r.period() == QuotaPeriod.LIFETIME ? "Quota à vie épuisé" : "Quota du mois épuisé";
    }

    static String byLabel(PackMovementRow r) {
        if (r.source() == UsagePackSource.MIGRATION) {
            return null;
        }
        if (r.actorName() != null && !r.actorName().isBlank()) {
            return r.actorName();
        }
        if (r.source() == UsagePackSource.PURCHASE) {
            return OWNER; // only the owner buys a pack
        }
        return r.actorId() == null ? AUTOMATIC : SOMEONE;
    }

    private static UsageMetric metric(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UsageMetric.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("metric", METRIC_INVALID);
        }
    }

    private static DomainException invalid(String field, String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
}

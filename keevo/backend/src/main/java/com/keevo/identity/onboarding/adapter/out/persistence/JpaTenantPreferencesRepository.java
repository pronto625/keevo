package com.keevo.identity.onboarding.adapter.out.persistence;

import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;

/**
 * JpaTenantPreferencesRepository — Persistence adapter for TenantPreferencesRepository port.
 *
 * <p>Maps between domain {@link TenantPreferences} records and JPA entities.
 * Multi-tenant routing is handled transparently by {@link SchemaAwareMultiTenantConnectionProvider}.
 * Story 7.5 — added update() and mapping for 9 new fields.
 */
@Component
public class JpaTenantPreferencesRepository implements TenantPreferencesRepository {

    private final TenantPreferencesJpaRepository jpaRepository;

    public JpaTenantPreferencesRepository(TenantPreferencesJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public TenantPreferences save(TenantPreferences prefs) {
        Instant now = Instant.now();
        TenantPreferencesJpaEntity entity = new TenantPreferencesJpaEntity(
            prefs.sectorType() != null ? prefs.sectorType().name() : null,
            LocalTime.parse(prefs.eodReportTime()),
            prefs.stockAlertEnabled(),
            prefs.createdAt() != null ? prefs.createdAt() : now,
            now
        );
        applyNewFields(entity, prefs);
        TenantPreferencesJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public TenantPreferences update(TenantPreferences prefs) {
        TenantPreferencesJpaEntity entity = jpaRepository.findById(prefs.id())
                .orElseThrow(() -> new IllegalStateException("TenantPreferences entity not found for update: " + prefs.id()));

        entity.setSectorType(prefs.sectorType() != null ? prefs.sectorType().name() : null);
        entity.setEodReportTime(LocalTime.parse(prefs.eodReportTime()));
        entity.setStockAlertEnabled(prefs.stockAlertEnabled());
        entity.setUpdatedAt(Instant.now());
        applyNewFields(entity, prefs);

        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public boolean hasOnboardingCompleted() {
        return jpaRepository.count() > 0;
    }

    @Override
    public Optional<TenantPreferences> findByCurrentTenant() {
        return jpaRepository.findTopByOrderByCreatedAtDesc()
            .map(this::toDomain);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private void applyNewFields(TenantPreferencesJpaEntity entity, TenantPreferences prefs) {
        entity.setEodReportEnabled(prefs.eodReportEnabled());
        entity.setEodReportChannel(prefs.eodReportChannel() != null
                ? prefs.eodReportChannel().name() : "WHATSAPP");
        entity.setWeeklyReportEnabled(prefs.weeklyReportEnabled());
        entity.setWeeklyReportDay(prefs.weeklyReportDay());
        entity.setWeeklyReportTime(prefs.weeklyReportTime() != null
                ? LocalTime.parse(prefs.weeklyReportTime()) : LocalTime.of(20, 0));
        entity.setWeeklyReportChannel(prefs.weeklyReportChannel() != null
                ? prefs.weeklyReportChannel().name() : "WHATSAPP");
        entity.setInventoryReportEnabled(prefs.inventoryReportEnabled());
        entity.setInventoryReportChannel(prefs.inventoryReportChannel() != null
                ? prefs.inventoryReportChannel().name() : "WHATSAPP");
        entity.setStockAlertChannel(prefs.stockAlertChannel() != null
                ? prefs.stockAlertChannel().name() : "PUSH");
        entity.setTrendNotificationEnabled(prefs.trendNotificationEnabled());
    }

    private TenantPreferences toDomain(TenantPreferencesJpaEntity entity) {
        SectorType sectorType = entity.getSectorType() != null
            ? SectorType.valueOf(entity.getSectorType())
            : null;
        return new TenantPreferences(
            entity.getId(),
            sectorType,
            entity.getEodReportTime().toString(),
            entity.isStockAlertEnabled(),
            entity.getCreatedAt(),
            entity.isEodReportEnabled(),
            ReportChannel.fromString(entity.getEodReportChannel()),
            entity.isWeeklyReportEnabled(),
            entity.getWeeklyReportDay(),
            entity.getWeeklyReportTime().toString(),
            ReportChannel.fromString(entity.getWeeklyReportChannel()),
            entity.isInventoryReportEnabled(),
            ReportChannel.fromString(entity.getInventoryReportChannel()),
            StockAlertChannel.fromString(entity.getStockAlertChannel()),
            entity.isTrendNotificationEnabled()
        );
    }
}


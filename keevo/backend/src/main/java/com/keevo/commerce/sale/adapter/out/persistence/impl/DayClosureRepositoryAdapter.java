package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.commerce.sale.adapter.out.persistence.entity.DayClosureJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.DayClosureSpringRepository;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * DayClosureRepositoryAdapter — Adapter bridging DayClosureRepository port
 * to Spring Data JPA.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@Component
public class DayClosureRepositoryAdapter implements DayClosureRepository {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final DayClosureSpringRepository springRepository;

    public DayClosureRepositoryAdapter(DayClosureSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public void save(DayClosure closure) {
        DayClosureJpaEntity entity = toEntity(closure);
        springRepository.save(entity);
    }

    @Override
    public boolean existsByStoreIdAndDate(UUID storeId, LocalDate date) {
        return springRepository.existsByStoreIdAndClosureDate(storeId, date);
    }

    @Override
    public List<DayClosure> findByStoreIdAndDate(UUID storeId, LocalDate date) {
        return springRepository.findByStoreIdAndClosureDate(storeId, date)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private DayClosureJpaEntity toEntity(DayClosure closure) {
        DayClosureJpaEntity entity = new DayClosureJpaEntity();
        entity.setId(closure.getId());
        entity.setStoreId(closure.getStoreId());
        entity.setActorId(closure.getActorId());
        entity.setClosedAt(closure.getClosedAt());
        entity.setClosureDate(closure.getClosedAt().atZone(WAT).toLocalDate());
        entity.setAutomatic(closure.isAutomatic());
        entity.setTenantId(closure.getTenantId());

        DayClosureSummary summary = closure.getSummary();
        entity.setTotalSales(summary.totalSales());
        entity.setTotalRevenue(summary.totalRevenue());
        entity.setTopProductId(summary.topProductId());
        entity.setTopProductName(summary.topProductName());
        entity.setTopProductQty(summary.topProductQty());
        entity.setCashAmount(summary.cashAmount());
        entity.setMomoAmount(summary.momoAmount());
        entity.setPendingSalesCount(summary.pendingSalesCount());
        entity.setPendingSalesTotal(summary.pendingSalesTotal());

        return entity;
    }

    private DayClosure toDomain(DayClosureJpaEntity entity) {
        DayClosureSummary summary = new DayClosureSummary(
                entity.getTotalSales(),
                entity.getTotalRevenue(),
                entity.getTopProductId(),
                entity.getTopProductName(),
                entity.getTopProductQty(),
                entity.getCashAmount(),
                entity.getMomoAmount(),
                entity.getPendingSalesCount(),
                entity.getPendingSalesTotal()
        );

        return new DayClosure(
                entity.getId(),
                entity.getStoreId(),
                entity.getActorId(),
                entity.getClosedAt(),
                summary,
                entity.isAutomatic(),
                entity.getTenantId()
        );
    }
}

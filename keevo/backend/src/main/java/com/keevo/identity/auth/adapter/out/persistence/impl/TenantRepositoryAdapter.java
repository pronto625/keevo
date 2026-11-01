package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.TenantSpringRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * TenantRepositoryAdapter — Adapter bridging the domain {@link TenantRepository} port
 * to the Spring Data JPA {@link TenantSpringRepository}.
 *
 * <p>Architecture layer: {@code adapter/out/persistence/impl} — infrastructure only.
 */
@Component
public class TenantRepositoryAdapter implements TenantRepository {

    private final TenantSpringRepository springRepository;
    private final PlanProfiles planProfiles;

    /** Without plan profiles: legacy limit columns from the built-in defaults (tests). */
    public TenantRepositoryAdapter(TenantSpringRepository springRepository) {
        this(springRepository, PlanProfiles.ENUM_DEFAULTS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TenantRepositoryAdapter(TenantSpringRepository springRepository, PlanProfiles planProfiles) {
        this.springRepository = springRepository;
        this.planProfiles = planProfiles;
    }

    @Override
    public Tenant save(Tenant tenant) {
        TenantJpaEntity saved = springRepository.save(toEntity(tenant));
        return toDomain(saved);
    }

    @Override
    public boolean existsByCode(String code) {
        return springRepository.existsByCode(code);
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Tenant> findBySchemaName(String schemaName) {
        return springRepository.findBySchemaName(schemaName).map(this::toDomain);
    }

    @Override
    public Optional<Tenant> findByCode(String tenantCode) {
        return springRepository.findByCode(tenantCode).map(this::toDomain);
    }

    @Override
    public java.util.List<Tenant> findDeletionsDueBy(Instant now) {
        return springRepository
                .findByStatusAndDeletionScheduledAtLessThanEqual(TenantStatus.DELETION_PENDING.name(), now)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean cancelPendingDeletion(String schemaName) {
        return springRepository.cancelPendingDeletion(schemaName) == 1;
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private TenantJpaEntity toEntity(Tenant t) {
        // Story 16.8: legacy tenants.max_* columns filled from the plan profile (no longer read for decisions)
        PlanProfile profile = planProfiles.profile(t.getPlanType());
        TenantJpaEntity entity = new TenantJpaEntity(
            t.getId(), t.getCode(), t.getSchemaName(),
            t.getName(), t.getStatus().name(), t.getPlanType().name(),
            profile.legacyMaxStores(),
            profile.legacyMaxProducts(),
            profile.legacyMaxEmployees()
        );
        entity.setDeletionScheduledAt(t.getDeletionScheduledAt());
        return entity;
    }

    private Tenant toDomain(TenantJpaEntity e) {
        return new Tenant(
            e.getId(), e.getCode(), e.getSchemaName(),
            e.getName() != null ? e.getName() : e.getCode(),
            TenantStatus.valueOf(e.getStatus()),
            PlanType.valueOf(e.getPlanType()),
            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now(),
            e.getDeletionScheduledAt()
        );
    }

    @Override
    public java.util.List<Tenant> findAll() {
        return springRepository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }
}

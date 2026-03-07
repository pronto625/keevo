package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.TenantSpringRepository;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
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

    public TenantRepositoryAdapter(TenantSpringRepository springRepository) {
        this.springRepository = springRepository;
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

    // ── Mapping ──────────────────────────────────────────────────────────────

    private TenantJpaEntity toEntity(Tenant t) {
        return new TenantJpaEntity(
            t.getId(), t.getCode(), t.getSchemaName(),
            t.getStatus().name(), t.getPlanType().name(),
            t.getPlanType().getMaxStores(),
            t.getPlanType().getMaxProducts(),
            t.getPlanType().getMaxEmployees()
        );
    }

    private Tenant toDomain(TenantJpaEntity e) {
        return new Tenant(
            e.getId(), e.getCode(), e.getSchemaName(),
            TenantStatus.valueOf(e.getStatus()),
            PlanType.valueOf(e.getPlanType()),
            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now()
        );
    }
}

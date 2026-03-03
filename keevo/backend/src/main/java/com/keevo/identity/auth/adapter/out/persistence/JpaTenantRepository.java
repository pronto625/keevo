package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JpaTenantRepository — Persistence adapter implementing the TenantRepository port.
 *
 * <p>Adapts between the domain Tenant model and TenantJpaEntity.
 * Maps JPA entity ↔ domain model (Anti-corruption layer).
 */
@Component
public class JpaTenantRepository implements TenantRepository {

    private final TenantJpaRepository jpaRepository;

    public JpaTenantRepository(TenantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Tenant save(Tenant tenant) {
        TenantJpaEntity entity = toJpaEntity(tenant);
        TenantJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private TenantJpaEntity toJpaEntity(Tenant tenant) {
        return new TenantJpaEntity(
            tenant.getId(),
            tenant.getCode(),
            tenant.getSchemaName(),
            tenant.getStatus().name(),
            tenant.getPlanType().name(),
            tenant.getPlanType().getMaxStores(),
            tenant.getPlanType().getMaxProducts(),
            tenant.getPlanType().getMaxEmployees()
        );
    }

    private Tenant toDomain(TenantJpaEntity entity) {
        return new Tenant(
            entity.getId(),
            entity.getCode(),
            entity.getSchemaName(),
            TenantStatus.valueOf(entity.getStatus()),
            PlanType.valueOf(entity.getPlanType()),
            entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now()
        );
    }
}

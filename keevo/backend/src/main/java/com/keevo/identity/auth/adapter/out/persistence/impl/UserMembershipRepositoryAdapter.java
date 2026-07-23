package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserTenantMembershipJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserTenantMembershipSpringRepository;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserMembershipRepositoryAdapter — Adapter for user-tenant membership persistence.
 *
 * <p>Story 1.7 — bridges {@link UserMembershipRepository} port to Spring Data JPA.
 * Translates {@link DataIntegrityViolationException} (UNIQUE constraint on user_id+tenant_id)
 * into a domain-safe {@code DomainException(MEMBERSHIP_ALREADY_EXISTS)}.
 */
@Component
public class UserMembershipRepositoryAdapter implements UserMembershipRepository {

    private final UserTenantMembershipSpringRepository springRepository;

    public UserMembershipRepositoryAdapter(UserTenantMembershipSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public UserTenantMembership save(UserTenantMembership membership) {
        try {
            UserTenantMembershipJpaEntity saved =
                    springRepository.save(toEntity(membership));
            return toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE constraint (user_id, tenant_id) violated
            throw new DomainException(ErrorCode.MEMBERSHIP_ALREADY_EXISTS);
        }
    }

    @Override
    public List<UserTenantMembership> findByUserId(UUID userId) {
        return springRepository.findByUserId(userId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<UserTenantMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId) {
        return springRepository.findByUserIdAndTenantId(userId, tenantId)
                .map(this::toDomain);
    }

    @Override
    public void deactivateByUserId(UUID userId) {
        List<UserTenantMembershipJpaEntity> memberships = springRepository.findByUserId(userId);
        for (UserTenantMembershipJpaEntity m : memberships) {
            m.setActive(false);
            springRepository.save(m);
        }
    }

    @Override
    public void reactivateByUserId(UUID userId) {
        List<UserTenantMembershipJpaEntity> memberships = springRepository.findByUserId(userId);
        for (UserTenantMembershipJpaEntity m : memberships) {
            m.setActive(true);
            springRepository.save(m);
        }
    }

    @Override
    public void updateRole(UUID userId, UUID tenantId, String newRole) {
        UserTenantMembershipJpaEntity entity = springRepository
                .findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.MEMBERSHIP_NOT_FOUND));
        entity.setRole(newRole);
        springRepository.save(entity);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private UserTenantMembershipJpaEntity toEntity(UserTenantMembership m) {
        return new UserTenantMembershipJpaEntity(
                m.getId(), m.getUserId(), m.getTenantId(), m.getRole(), m.isActive());
    }

    private UserTenantMembership toDomain(UserTenantMembershipJpaEntity e) {
        return new UserTenantMembership(
                e.getId(), e.getUserId(), e.getTenantId(),
                e.getRole(), e.isActive(), e.getCreatedAt());
    }
}

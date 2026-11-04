package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.port.in.CreateCompanyCommand;
import com.keevo.identity.auth.domain.port.in.CreateCompanyUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Story 19.2 — company creation for a signed-in account without company (an account created by a sign-in code).
 *
 * <p>Façade: loginToken check (same errors as {@code select-tenant}, {@link LoginTokens}) → user row locked (two
 * concurrent calls run one after the other) → refused with {@code COMPANY_ALREADY_EXISTS} when the user already owns
 * an active company, or when its global role is not OWNER → {@link OwnerTenantProvisioner}, the path {@code /auth/register} uses (trial plan, OWNER
 * membership and role, session, {@code UserRegisteredEvent}).
 *
 * <p>Complexity: one lock and one read by primary key, one memberships JOIN, then the provisioning.
 */
@Service
public class CreateCompanyService implements CreateCompanyUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final OwnerTenantProvisioner ownerTenantProvisioner;

    public CreateCompanyService(JwtTokenProvider jwtTokenProvider,
                                UserRepository userRepository,
                                OwnerTenantProvisioner ownerTenantProvisioner) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.ownerTenantProvisioner = ownerTenantProvisioner;
    }

    @Override
    @Transactional
    public RegistrationResult create(CreateCompanyCommand command) {
        UUID userId = LoginTokens.parseUserId(jwtTokenProvider, command.loginToken());
        if (!userRepository.lockById(userId)) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        // Global role other than OWNER (an employee elsewhere, the super-admin): tokens built from users.role (e.g.
        // after a password change) would not be OWNER in the new company. Multi-role accounts are story 19.6.
        boolean ownerAccount = userRepository.findById(userId).map(u -> u.getRole() == Role.OWNER).orElse(false);
        boolean ownsCompany = userRepository.findMembershipsWithTenantInfo(userId).stream()
                .anyMatch(m -> "OWNER".equals(m.role()));
        if (!ownerAccount || ownsCompany) {
            throw new DomainException(ErrorCode.COMPANY_ALREADY_EXISTS);
        }
        return ownerTenantProvisioner.provision(() -> userId, command.device());
    }
}

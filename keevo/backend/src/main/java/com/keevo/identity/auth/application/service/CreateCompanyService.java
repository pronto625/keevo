package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.port.in.CreateCompanyCommand;
import com.keevo.identity.auth.domain.port.in.CreateCompanyUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
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
 * concurrent calls run one after the other) → refused with {@code COMPANY_ALREADY_EXISTS} when the user already holds
 * an active OWNER membership, or is the platform administrator → {@link OwnerTenantProvisioner}, the path
 * {@code /auth/register} uses (trial plan, OWNER membership and role, session, {@code UserRegisteredEvent}).
 *
 * <p>Story 19.6 — judged on memberships, not on the global role: an employee of another company may create his own
 * (the session role comes from the membership, so the new company's tokens say OWNER).
 *
 * <p>Complexity: one lock and one read by primary key, one {@code EXISTS} on memberships, then the provisioning.
 */
@Service
public class CreateCompanyService implements CreateCompanyUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final OwnerTenantProvisioner ownerTenantProvisioner;

    public CreateCompanyService(JwtTokenProvider jwtTokenProvider,
                                UserRepository userRepository,
                                UserMembershipRepository membershipRepository,
                                OwnerTenantProvisioner ownerTenantProvisioner) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.ownerTenantProvisioner = ownerTenantProvisioner;
    }

    @Override
    @Transactional
    public RegistrationResult create(CreateCompanyCommand command) {
        UUID userId = LoginTokens.parseUserId(jwtTokenProvider, command.loginToken());
        if (!userRepository.lockById(userId)) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        // Story 19.6 — the platform administrator never owns a company; anyone else may, unless he already owns one.
        boolean superAdmin = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.TOKEN_INVALID))
                .getRole() == Role.SUPER_ADMIN;
        if (superAdmin || membershipRepository.hasActiveOwnerMembership(userId)) {
            throw new DomainException(ErrorCode.COMPANY_ALREADY_EXISTS);
        }
        return ownerTenantProvisioner.provision(() -> userId, command.device());
    }
}

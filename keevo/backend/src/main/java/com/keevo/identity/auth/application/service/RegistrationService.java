package com.keevo.identity.auth.application.service;

import com.keevo.shared.domain.PasswordLimits;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegistrationService — Orchestrates user registration and tenant provisioning.
 *
 * <p>GoF Pattern: Façade — single entry point that coordinates:
 * User uniqueness check → Password hashing → {@link OwnerTenantProvisioner} (tenant, user creation, membership,
 * owner role, session, event).
 *
 * <p>The entire method is @Transactional: any failure rolls back the complete operation.
 * This guarantees AC4 (no partial tenant state on failure).
 *
 * <p>Architecture rules enforced:
 * - ActorId passed explicitly in command (never via SecurityContextHolder)
 * - No HTTP types in this class
 * - Password never logged
 */
@Service
public class RegistrationService implements RegisterUserUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OwnerTenantProvisioner ownerTenantProvisioner;

    public RegistrationService(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                OwnerTenantProvisioner ownerTenantProvisioner) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.ownerTenantProvisioner = ownerTenantProvisioner;
    }

    @Override
    @Transactional
    public RegistrationResult register(RegisterUserCommand command) {
        // Story 12.12 — canonical +<digits> form before uniqueness check and persist
        String phone = PhoneNumbers.normalize(command.phoneNumber());

        // Story 12.12 — BCrypt rejects > 72 UTF-8 bytes (would be a 500); DTO @Size counts chars
        if (PasswordLimits.exceedsBcryptLimit(command.password())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "password must not exceed 72 bytes");
        }

        // 1. Check phone number uniqueness (AC3 — USER_ALREADY_EXISTS)
        if (userRepository.existsByPhoneNumber(phone)) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 2. Hash password (bcrypt ≥12 configured in SecurityConfig)
        //    NEVER log password — only the hash
        String passwordHash = passwordEncoder.encode(command.password());

        // 3–7. Story 19.2 — tenant, user (saved once the tenant exists), OWNER membership, owner role, session and
        //      event through the provisioner shared with create-company. Same order and transaction as before.
        return ownerTenantProvisioner.provision(
                () -> userRepository.save(User.newOwner(phone, passwordHash)).getId(), null);
    }
}

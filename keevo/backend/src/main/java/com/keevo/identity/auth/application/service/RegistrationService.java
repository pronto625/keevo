package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegistrationService — Orchestrates user registration and tenant provisioning.
 *
 * <p>GoF Pattern: Façade — single entry point that coordinates:
 * User uniqueness check → Password hashing → Tenant provisioning → User creation → JWT → Event.
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
    private final TenantFactory tenantFactory;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantSchemaPort tenantSchemaPort;

    public RegistrationService(UserRepository userRepository,
                                TenantFactory tenantFactory,
                                PasswordEncoder passwordEncoder,
                                JwtTokenProvider jwtTokenProvider,
                                ApplicationEventPublisher eventPublisher,
                                TenantSchemaPort tenantSchemaPort) {
        this.userRepository = userRepository;
        this.tenantFactory = tenantFactory;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.eventPublisher = eventPublisher;
        this.tenantSchemaPort = tenantSchemaPort;
    }

    @Override
    @Transactional
    public RegistrationResult register(RegisterUserCommand command) {
        // 1. Check phone number uniqueness (AC3 — USER_ALREADY_EXISTS)
        if (userRepository.existsByPhoneNumber(command.phoneNumber())) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 2. Hash password (bcrypt ≥12 configured in SecurityConfig)
        //    NEVER log password — only the hash
        String passwordHash = passwordEncoder.encode(command.password());

        // 3. Provision tenant (schema creation + Flyway migrations) — AC1, AC2
        //    Factory pattern: TenantFactory handles all provisioning complexity
        //    If this fails → @Transactional rolls back everything (AC4)
        Tenant tenant = tenantFactory.create();

        // 4. Create user domain object and persist
        User user = User.newOwner(command.phoneNumber(), passwordHash, tenant.getId());
        User savedUser = userRepository.save(user);

        // 5. Assign OWNER role in the tenant schema (AC1 — user_roles table)
        //    Must be called AFTER userRepository.save() so the userId is known.
        //    Runs within the same @Transactional boundary — rolls back on failure.
        tenantSchemaPort.assignOwnerRole(tenant.getSchemaName(), savedUser.getId());

        // 6. Generate stub JWT token (Story 1.3 implements real JWT)
        String token = jwtTokenProvider.generateToken(
            savedUser.getId().toString(),
            tenant.getId().toString()
        );

        // 7. Publish domain event (Observer pattern → AuditEventListener)
        eventPublisher.publishEvent(
            UserRegisteredEvent.of(savedUser.getId(), tenant.getId(),
                tenant.getCode(), tenant.getSchemaName())
        );

        return new RegistrationResult(
            tenant.getCode(),
            token,
            savedUser.getId().toString(),
            tenant.getId().toString()
        );
    }
}

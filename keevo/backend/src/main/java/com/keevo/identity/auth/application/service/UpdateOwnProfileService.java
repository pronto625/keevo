package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.in.UpdateOwnProfileUseCase;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.auth.UserProfileUpdatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 22.19 (D17) — {@code PUT /api/v1/auth/profile}: the user's own name on {@code public.users} and, for a store
 * member, on his employee record of the current company (where {@code GET /auth/profile} reads it). Audited
 * {@code PROFILE_UPDATED} when a name really changed. Returns the profile as the GET does.
 */
@Service
public class UpdateOwnProfileService implements UpdateOwnProfileUseCase {

    static final int MAX_NAME = 50;

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final GetUserProfileUseCase profiles;
    private final ApplicationEventPublisher events;

    public UpdateOwnProfileService(UserRepository userRepository, EmployeeRepository employeeRepository,
                                   GetUserProfileUseCase profiles, ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.profiles = profiles;
        this.events = events;
    }

    @Override
    @Transactional
    public UserProfileData update(UUID userId, String tenantId, String firstName, String lastName) {
        String first = name("firstName", firstName, "Le prénom");
        String last = name("lastName", lastName, "Le nom");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));
        UserProfileData before = profiles.execute(userId, tenantId);
        if (!first.equals(user.getFirstName()) || !last.equals(user.getLastName())) {
            userRepository.save(user.withNames(first, last));
        }
        // a store member's shown name lives on his employee record of this company
        if (!TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantId)) {
            employeeRepository.findByUserId(userId)
                    .filter(e -> !first.equals(e.getFirstName()) || !last.equals(e.getLastName()))
                    .ifPresent(e -> employeeRepository.updateProfile(e.getId(), first, last));
        }
        if (!Objects.equals(before.firstName(), first) || !Objects.equals(before.lastName(), last)) {
            events.publishEvent(new UserProfileUpdatedEvent(tenantId, userId, before.firstName(), before.lastName(),
                    first, last, Instant.now()));
        }
        return profiles.execute(userId, tenantId);
    }

    /** Trimmed, 1–{@value #MAX_NAME} characters, else a French VALIDATION_ERROR naming the field. */
    private static String name(String field, String value, String label) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > MAX_NAME) {
            String reason = label + " doit contenir entre 1 et " + MAX_NAME + " caractères";
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, reason, Map.of("field", field,
                    "reason", reason));
        }
        return v;
    }
}

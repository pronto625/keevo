package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * UserProfileService — Facade for user profile retrieval (Story 8.6 AC3, AC4).
 *
 * <p>GoF Facade pattern: hides coordination of UserRepository, EmployeeRepository
 * and StoreRepository behind a single {@link #execute} method.
 *
 * <p>EMPLOYEE: firstName, lastName, storeId, storeName resolved from employee + store records.
 * OWNER: no employee record — firstName/lastName come from {@code public.users} (Story 19.1, null when never set),
 * store fields are null.
 *
 * <p>Story 19.1 — {@code phoneNumber} is never null: a phone-less (email/username-only) account gets {@code ""},
 * because the installed V1 Flutter app parses it as a non-null string.
 *
 * <p>Open/Closed: adding new profile fields requires only extending {@link UserProfileData}
 * and this service — the port and controller remain unchanged.
 */
@Service
public class UserProfileService implements GetUserProfileUseCase {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreQueryPort storeQuery;
    private final TenantRepository tenantRepository;
    private final UserMembershipRepository membershipRepository;
    private final SessionTokenIssuer sessionTokenIssuer;

    public UserProfileService(UserRepository userRepository,
                               EmployeeRepository employeeRepository,
                               StoreQueryPort storeQuery,
                               TenantRepository tenantRepository,
                               UserMembershipRepository membershipRepository,
                               SessionTokenIssuer sessionTokenIssuer) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.storeQuery = storeQuery;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.sessionTokenIssuer = sessionTokenIssuer;
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileData execute(UUID userId, String tenantId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));

        // Story 16.11 — role and account status from the membership / account (never the token).
        Optional<Tenant> tenant = tenantRepository.findBySchemaName(tenantId);
        String tenantStatus = tenant.map(t -> t.getStatus().name()).orElse("ACTIVE");
        Optional<String> membershipRole = tenant.flatMap(t ->
                        membershipRepository.findByUserIdAndTenantId(userId, t.getId()))
                .map(UserTenantMembership::getRole);
        boolean platformAdmin = TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantId);
        boolean onboardingCompleted = platformAdmin || sessionTokenIssuer.isOnboardingCompleted(tenantId);

        Optional<Employee> employeeOpt = platformAdmin ? Optional.empty() : employeeRepository.findByUserId(userId);
        String role = membershipRole.orElse(employeeOpt.isPresent() ? "EMPLOYEE" : "OWNER");

        if (employeeOpt.isPresent()) {
            Employee emp = employeeOpt.get();
            boolean employeeRole = Role.isStoreMember(role); // Story 19.5: EMPLOYEE, MANAGER, VIEWER
            String storeName = employeeRole && emp.getStoreId() != null
                    ? storeQuery.findById(TenantContext.getCurrentTenant(), emp.getStoreId())
                            .map(s -> s.name()).orElse(null)
                    : null;
            return new UserProfileData(
                    user.getId(),
                    phoneForV1(user),
                    role,
                    emp.getFirstName(),
                    emp.getLastName(),
                    employeeRole && emp.getStoreId() != null ? emp.getStoreId().toString() : null,
                    storeName,
                    employeeRole && emp.isPasswordChangeRequired(),
                    tenantStatus,
                    onboardingCompleted,
                    shownUsername(user)
            );
        }

        // Story 19.1 — owner names now live on public.users (null when never set, as before).
        return new UserProfileData(
                user.getId(),
                phoneForV1(user),
                role,
                user.getFirstName(),
                user.getLastName(),
                null,
                null,
                false,
                tenantStatus,
                onboardingCompleted,
                shownUsername(user)
        );
    }

    /** Story 19.4 — the sign-in username; a social account's technical username is never shown. */
    private static String shownUsername(User user) {
        return Usernames.isTechnical(user.getUsername()) ? null : user.getUsername();
    }

    /** Story 19.1 — null-object at the read boundary: V1 clients expect a non-null phone string. */
    private static String phoneForV1(User user) {
        return user.getPhoneNumber() != null ? user.getPhoneNumber() : "";
    }
}

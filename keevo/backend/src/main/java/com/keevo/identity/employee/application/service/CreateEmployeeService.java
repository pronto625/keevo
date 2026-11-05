package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeCreatedEvent;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.TempPasswordFactory;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * CreateEmployeeService — Orchestrates employee creation (Story 3.5 AC1).
 *
 * <p>Story 19.4 — the member is identified by a phone, a username (Bref members), or both. Complexity: one indexed
 * lookup per identifier given, then today's flow.
 *
 * <p>Story 19.6 — find-or-attach: when the phone (then the username) already belongs to an account, that account is
 * attached to this company (membership with the requested role + employee row, password untouched, no forced change,
 * the person notified after commit by {@link MemberAddedNotifier}) instead of failing. Refused when it is already a
 * member here ({@code MEMBER_ALREADY_IN_COMPANY}, or {@code MEMBER_INACTIVE_IN_COMPANY} when deactivated), is the
 * platform administrator or an inactive account, or when phone and username point at two different accounts or only
 * one of them matches ({@code USER_ALREADY_EXISTS}). The global {@code users.role} is never
 * changed. Complexity: + one {@code (user_id, tenant_id)} index lookup.
 */
@Service
public class CreateEmployeeService implements CreateEmployeeUseCase {

    static final String PARTIAL_MATCH_MESSAGE =
            "Ce téléphone ou cet identifiant appartient déjà à un compte : n'indiquez que celui-là";

    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^\\+?[0-9]{8,15}$");

    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final EmployeeRepository employeeRepository;
    private final PlanLimitCheck planLimit;
    private final EmployeeCountPort employeeCountPort;
    private final TenantPlanQuery tenantPlanQuery;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionLockPort transactionLock;
    private final MemberAddedNotifier memberAddedNotifier;

    public CreateEmployeeService(UserRepository userRepository,
                                  UserMembershipRepository membershipRepository,
                                  EmployeeRepository employeeRepository,
                                  PlanLimitCheck planLimit,
                                  EmployeeCountPort employeeCountPort,
                                  TenantPlanQuery tenantPlanQuery,
                                  TenantRepository tenantRepository,
                                  PasswordEncoder passwordEncoder,
                                  ApplicationEventPublisher eventPublisher,
                                  TransactionLockPort transactionLock,
                                  MemberAddedNotifier memberAddedNotifier) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.employeeRepository = employeeRepository;
        this.planLimit = planLimit;
        this.employeeCountPort = employeeCountPort;
        this.tenantPlanQuery = tenantPlanQuery;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.transactionLock = transactionLock;
        this.memberAddedNotifier = memberAddedNotifier;
    }

    @Override
    @Transactional
    public CreateEmployeeResult execute(CreateEmployeeCommand command) {
        // Story 19.4 — a phone, a username, or both; a blank one counts as absent.
        String rawPhone = User.normalizeIdentifier(command.phoneNumber());
        String rawUsername = User.normalizeIdentifier(command.username());
        if (rawPhone == null && rawUsername == null) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, Usernames.IDENTIFIER_REQUIRED_MESSAGE);
        }
        // Story 12.12 — same format rule as UpdateEmployeeService, then canonical +<digits> form
        // before uniqueness check and persist (never store e.g. "+abc" as an identity).
        String phone = null;
        if (rawPhone != null) {
            if (!PHONE_PATTERN.matcher(rawPhone).matches()) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid phone number format");
            }
            phone = PhoneNumbers.normalize(rawPhone);
        }
        String username = rawUsername != null ? Usernames.requireValid(rawUsername) : null;
        // Story 19.5 — a member is created as EMPLOYEE (default), MANAGER or VIEWER; owners only by role change.
        Role role = command.role() == null || command.role().isBlank() ? Role.EMPLOYEE : Role.parse(command.role());
        if (role == null || !role.isStoreMember()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "role must be MANAGER, EMPLOYEE or VIEWER");
        }

        // 1. Story 19.6 — an identifier that already belongs to an account: attach that account (find-or-attach).
        // The unique indexes stay the source of truth for a concurrent creation (insertWithUsername / save).
        User existing = findExistingAccount(phone, username);
        if (existing != null) {
            return attachExistingAccount(existing, command, role);
        }

        // 2. Enforce plan limit
        enforcePlanLimit(command);

        // 3. Generate temp password
        String tempPassword = TempPasswordFactory.generate();
        String hash = passwordEncoder.encode(tempPassword);

        // 4. Create user in public.users
        User newUser = User.newEmployee(phone, username, hash);
        if (role != Role.EMPLOYEE) {
            newUser = newUser.withRole(role); // global role kept in step with the membership, as on a role change
        }
        newUser = username == null ? userRepository.save(newUser) : insertWithUsername(newUser);

        // 5. Create membership in public.user_tenant_memberships
        String schemaName = TenantContext.getCurrentTenant();
        var tenant = tenantRepository.findBySchemaName(schemaName)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        membershipRepository.save(
                UserTenantMembership.create(newUser.getId(), tenant.getId(), role.name()));

        // 6. Create employee in kv_xxx.employees
        Employee employee = Employee.create(
                newUser.getId(), command.storeId(), command.firstName(), command.lastName());
        employee = employeeRepository.save(employee);

        // 7. Publish event
        eventPublisher.publishEvent(new EmployeeCreatedEvent(
                command.actorId(), newUser.getId(), employee.getId(),
                command.storeId(), Instant.now()));

        return new CreateEmployeeResult(employee, tempPassword);
    }

    /**
     * Story 19.6 — the account holding the phone, else the one holding the username; {@code null} when neither
     * exists. Phone and username of two different accounts, or the platform administrator's account, answer
     * {@code USER_ALREADY_EXISTS}.
     */
    private User findExistingAccount(String phone, String username) {
        User byPhone = phone == null ? null : userRepository.findByPhoneNumber(phone).orElse(null);
        User byUsername = username == null ? null : userRepository.findByUsername(username).orElse(null);
        if (byPhone != null && byUsername != null && !byPhone.getId().equals(byUsername.getId())) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "phone and username of two different accounts");
        }
        // Both given, only one matches: the other identifier would be silently dropped (never written on an
        // existing account) — refused, the owner gives only the matching one.
        if (phone != null && username != null && (byPhone == null) != (byUsername == null)) {
            throw DomainException.userFacing(ErrorCode.USER_ALREADY_EXISTS, PARTIAL_MATCH_MESSAGE);
        }
        User found = byPhone != null ? byPhone : byUsername;
        if (found != null && (found.getRole() == Role.SUPER_ADMIN || !found.isActive())) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "platform administrator or inactive account");
        }
        return found;
    }

    /**
     * Story 19.6 — adds an existing account to this company: membership with the requested role, employee row with
     * the request's names and store, no password change and no forced change, {@code EmployeeCreatedEvent}, the
     * person notified after commit. Plan limits as for a new member.
     */
    private CreateEmployeeResult attachExistingAccount(User account, CreateEmployeeCommand command, Role role) {
        String schemaName = TenantContext.getCurrentTenant();
        var tenant = tenantRepository.findBySchemaName(schemaName)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        // Per-tenant lock BEFORE the membership check: two concurrent attaches of the same account are serialised,
        // the second sees the first's membership (no unique-key failure at commit).
        lockEmployeeLimit();
        var here = membershipRepository.findByUserIdAndTenantId(account.getId(), tenant.getId());
        if (here.isPresent()) {
            throw new DomainException(here.get().isActive()
                    ? ErrorCode.MEMBER_ALREADY_IN_COMPANY : ErrorCode.MEMBER_INACTIVE_IN_COMPANY);
        }
        checkPlanLimit(command);

        membershipRepository.save(UserTenantMembership.create(account.getId(), tenant.getId(), role.name()));
        Employee employee = employeeRepository.save(Employee.createForExistingAccount(
                account.getId(), command.storeId(), command.firstName(), command.lastName()));

        eventPublisher.publishEvent(new EmployeeCreatedEvent(
                command.actorId(), account.getId(), employee.getId(),
                command.storeId(), Instant.now()));
        memberAddedNotifier.notifyAfterCommit(account, schemaName, tenant.getName(), role);
        return new CreateEmployeeResult(employee, null, true);
    }

    /**
     * Plan limit — v1s-13-13: per-tenant lock BEFORE the count so two concurrent creates at max−1 cannot both pass
     * (the JdbcTemplate count sees the winner's commit, which happens before the lock is released).
     */
    private void enforcePlanLimit(CreateEmployeeCommand command) {
        lockEmployeeLimit();
        checkPlanLimit(command);
    }

    private void lockEmployeeLimit() {
        transactionLock.lock("employee-limit:" + TenantContext.getCurrentTenant());
    }

    private void checkPlanLimit(CreateEmployeeCommand command) {
        var plan = tenantPlanQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        // Story 19.7b — tenant and target-store counts in one query; per-store limit after the tenant-wide one
        var counts = employeeCountPort.countActive(command.storeId(), null);
        planLimit.checkEmployeeLimit(plan, counts.tenant());
        if (plan.maxEmployeesPerStore() != null && command.storeId() != null) {
            planLimit.checkStoreEmployeeLimit(plan, counts.store());
        }
    }

    /**
     * Story 19.4 — conflict-safe insert ({@code ON CONFLICT DO NOTHING}): a concurrent creation of the same username
     * (case-insensitive {@code uq_users_username_lower}) or phone answers 409 instead of failing the transaction at
     * commit with a 500. Pattern: unique constraint as the source of truth, mapped to the pre-check's error.
     */
    private User insertWithUsername(User newUser) {
        if (userRepository.insertIfAbsent(newUser)) {
            return newUser;
        }
        // Without a phone only the username can have collided (the id is random).
        if (!newUser.hasPhoneNumber() || userRepository.findByUsername(newUser.getUsername()).isPresent()) {
            throw new DomainException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "phone number already registered");
    }
}

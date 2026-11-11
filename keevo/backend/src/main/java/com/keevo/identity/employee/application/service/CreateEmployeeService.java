package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeCreatedEvent;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.model.TempPasswordFactory;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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
 * <p>Story 19.6b — an identifier that already belongs to an account never attaches it: the account is invited
 * ({@link MembershipInvitationService#invite}) and only the person's acceptance makes it a member. The order hides the
 * account from the inviting company: per-company lock and plan ceiling first (both paths), then the lookup; both
 * identifiers given while one is already used → {@code USER_ALREADY_EXISTS} without saying which nor whose; an existing
 * account already member here → {@code MEMBER_ALREADY_IN_COMPANY} / {@code MEMBER_INACTIVE_IN_COMPANY} (the company
 * knows its members); otherwise the neutral invitation result, whatever the account's state. The global
 * {@code users.role} is never changed. Complexity: + one {@code (user_id, tenant_id)} index lookup, one count, one
 * upsert.
 */
@Service
public class CreateEmployeeService implements CreateEmployeeUseCase {

    static final String PARTIAL_MATCH_MESSAGE = "Ce téléphone ou cet identifiant est déjà utilisé : indiquez seulement "
            + "le téléphone ou seulement l'identifiant de la personne";

    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^\\+?[0-9]{8,15}$");

    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeePlanLimit employeePlanLimit;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final MembershipInvitationService invitationService;

    public CreateEmployeeService(UserRepository userRepository,
                                  UserMembershipRepository membershipRepository,
                                  EmployeeRepository employeeRepository,
                                  EmployeePlanLimit employeePlanLimit,
                                  TenantRepository tenantRepository,
                                  PasswordEncoder passwordEncoder,
                                  ApplicationEventPublisher eventPublisher,
                                  MembershipInvitationService invitationService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.employeeRepository = employeeRepository;
        this.employeePlanLimit = employeePlanLimit;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.invitationService = invitationService;
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

        // 1. Plan limit under the per-company lock, BEFORE the lookup (Story 19.6b): a full plan answers the same
        // whatever the identifier, and two concurrent arrivals are serialised (v1s-13-13).
        employeePlanLimit.lockAndCheck(command.storeId());

        // 2. Story 19.6b — an identifier that already belongs to an account: invitation, never an attach.
        // The unique indexes stay the source of truth for a concurrent creation (insertWithUsername / save).
        User existing = findExistingAccount(phone, username);
        if (existing != null) {
            if (!command.invitationAllowed()) { // V1 sync push: the pre-19.6 refusal, nothing stored
                throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "identifier already registered");
            }
            return phone != null
                    ? inviteExistingAccount(existing, command, role, MembershipInvitation.IdentifierKind.PHONE, phone)
                    : inviteExistingAccount(existing, command, role, MembershipInvitation.IdentifierKind.USERNAME,
                            username);
        }

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
        Tenant tenant = currentTenant();
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
     * The account holding the phone, else the one holding the username; {@code null} when neither exists. Story 19.6b
     * — when both identifiers are given and either is already used, {@code USER_ALREADY_EXISTS} with one message that
     * says neither which one nor whether they are the same account.
     */
    private User findExistingAccount(String phone, String username) {
        User byPhone = phone == null ? null : userRepository.findByPhoneNumber(phone).orElse(null);
        User byUsername = username == null ? null : userRepository.findByUsername(username).orElse(null);
        if (phone != null && username != null && (byPhone != null || byUsername != null)) {
            throw DomainException.userFacing(ErrorCode.USER_ALREADY_EXISTS, PARTIAL_MATCH_MESSAGE);
        }
        return byPhone != null ? byPhone : byUsername;
    }

    /**
     * Story 19.6b — an existing account: refused when it is already a member here (active or deactivated), otherwise
     * invited (neutral result, see {@link MembershipInvitationService#invite}).
     */
    private CreateEmployeeResult inviteExistingAccount(User account, CreateEmployeeCommand command, Role role,
                                                       MembershipInvitation.IdentifierKind kind, String identifier) {
        Tenant tenant = currentTenant();
        // after the per-company lock: a concurrent acceptance of the same person is already visible
        var here = membershipRepository.findByUserIdAndTenantId(account.getId(), tenant.getId());
        if (here.isPresent()) {
            throw new DomainException(here.get().isActive()
                    ? ErrorCode.MEMBER_ALREADY_IN_COMPANY : ErrorCode.MEMBER_INACTIVE_IN_COMPANY);
        }
        invitationService.invite(account, tenant, command, role, kind, identifier);
        return CreateEmployeeResult.pendingInvitation();
    }

    private Tenant currentTenant() {
        return tenantRepository.findBySchemaName(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
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

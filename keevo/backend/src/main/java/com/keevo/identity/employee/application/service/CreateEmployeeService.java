package com.keevo.identity.employee.application.service;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.identity.auth.domain.model.User;
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
 */
@Service
public class CreateEmployeeService implements CreateEmployeeUseCase {

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

    public CreateEmployeeService(UserRepository userRepository,
                                  UserMembershipRepository membershipRepository,
                                  EmployeeRepository employeeRepository,
                                  PlanLimitCheck planLimit,
                                  EmployeeCountPort employeeCountPort,
                                  TenantPlanQuery tenantPlanQuery,
                                  TenantRepository tenantRepository,
                                  PasswordEncoder passwordEncoder,
                                  ApplicationEventPublisher eventPublisher,
                                  TransactionLockPort transactionLock) {
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
    }

    @Override
    @Transactional
    public CreateEmployeeResult execute(CreateEmployeeCommand command) {
        // Story 12.12 — same format rule as UpdateEmployeeService, then canonical +<digits> form
        // before uniqueness check and persist (never store e.g. "+abc" as an identity).
        if (command.phoneNumber() == null || !PHONE_PATTERN.matcher(command.phoneNumber()).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid phone number format");
        }
        String phone = PhoneNumbers.normalize(command.phoneNumber());

        // 1. Check phone uniqueness
        if (userRepository.existsByPhoneNumber(phone)) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "phone number already registered");
        }

        // 2. Enforce plan limit — v1s-13-13: per-tenant lock BEFORE the count so two concurrent
        // creates at max−1 cannot both pass (the JdbcTemplate count sees the winner's commit,
        // which happens before the lock is released).
        transactionLock.lock("employee-limit:" + TenantContext.getCurrentTenant());
        var plan = tenantPlanQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        int currentCount = employeeCountPort.countActiveEmployees();
        planLimit.checkEmployeeLimit(plan, currentCount);

        // 3. Generate temp password
        String tempPassword = TempPasswordFactory.generate();
        String hash = passwordEncoder.encode(tempPassword);

        // 4. Create user in public.users
        User newUser = User.newEmployee(phone, hash);
        newUser = userRepository.save(newUser);

        // 5. Create membership in public.user_tenant_memberships
        String schemaName = TenantContext.getCurrentTenant();
        var tenant = tenantRepository.findBySchemaName(schemaName)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));
        membershipRepository.save(
                UserTenantMembership.create(newUser.getId(), tenant.getId(), "EMPLOYEE"));

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
}

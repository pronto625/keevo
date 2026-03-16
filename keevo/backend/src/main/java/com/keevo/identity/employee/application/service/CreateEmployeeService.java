package com.keevo.identity.employee.application.service;

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
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
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

    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final EmployeeRepository employeeRepository;
    private final PlanLimitGuard planLimitGuard;
    private final EmployeeCountPort employeeCountPort;
    private final SubscriptionRepository subscriptionRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public CreateEmployeeService(UserRepository userRepository,
                                  UserMembershipRepository membershipRepository,
                                  EmployeeRepository employeeRepository,
                                  PlanLimitGuard planLimitGuard,
                                  EmployeeCountPort employeeCountPort,
                                  SubscriptionRepository subscriptionRepository,
                                  TenantRepository tenantRepository,
                                  PasswordEncoder passwordEncoder,
                                  ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.employeeRepository = employeeRepository;
        this.planLimitGuard = planLimitGuard;
        this.employeeCountPort = employeeCountPort;
        this.subscriptionRepository = subscriptionRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public CreateEmployeeResult execute(CreateEmployeeCommand command) {
        // 1. Check phone uniqueness
        if (userRepository.existsByPhoneNumber(command.phoneNumber())) {
            throw new DomainException(ErrorCode.USER_ALREADY_EXISTS, "phone number already registered");
        }

        // 2. Enforce plan limit
        var subscription = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        int currentCount = employeeCountPort.countActiveEmployees();
        planLimitGuard.checkEmployeeLimit(subscription.getPlanType(), currentCount);

        // 3. Generate temp password
        String tempPassword = TempPasswordFactory.generate();
        String hash = passwordEncoder.encode(tempPassword);

        // 4. Create user in public.users
        User newUser = User.newEmployee(command.phoneNumber(), hash);
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

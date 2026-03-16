package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeCreatedEvent;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CreateEmployeeServiceTest — TDD RED tests (Story 3.5 — AC1, AC2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateEmployeeService")
class CreateEmployeeServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock EmployeeCountPort employeeCountPort;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks CreateEmployeeService createEmployeeService;

    UUID actorId;
    UUID storeId;
    Subscription premiumSubscription;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        premiumSubscription = mock(Subscription.class);
        lenient().when(premiumSubscription.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
    }

    @Test
    @DisplayName("execute() should create user, membership, and employee when valid command")
    void execute_shouldCreateUserMembershipAndEmployee_whenValidCommand() {
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActiveEmployees()).thenReturn(0);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000001", "Loïc", "Nkoulou", storeId);

        CreateEmployeeResult result = createEmployeeService.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.employee()).isNotNull();
        assertThat(result.employee().getFirstName()).isEqualTo("Loïc");
        assertThat(result.employee().getLastName()).isEqualTo("Nkoulou");
        assertThat(result.employee().getStoreId()).isEqualTo(storeId);
        assertThat(result.employee().isPasswordChangeRequired()).isTrue();
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword().length()).isEqualTo(12);

        verify(userRepository).save(any(User.class));
        verify(membershipRepository).save(any(UserTenantMembership.class));
        verify(employeeRepository).save(any(Employee.class));
    }

    @Test
    @DisplayName("execute() should throw PLAN_LIMIT_EXCEEDED when employee count reaches max")
    void execute_shouldThrowPlanLimitExceeded_whenEmployeeCountReachesMax() {
        Subscription freeSub = mock(Subscription.class);
        when(freeSub.getPlanType()).thenReturn(PlanType.FREE);

        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub));
        when(employeeCountPort.countActiveEmployees()).thenReturn(5);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"))
                .when(planLimitGuard).checkEmployeeLimit(PlanType.FREE, 5);

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000002", "Test", "User", storeId);

        assertThatThrownBy(() -> createEmployeeService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
    }

    @Test
    @DisplayName("execute() should throw USER_ALREADY_EXISTS when phone number exists")
    void execute_shouldThrowPhoneAlreadyRegistered_whenPhoneNumberExists() {
        when(userRepository.existsByPhoneNumber("+237690000003")).thenReturn(true);

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000003", "Test", "User", storeId);

        assertThatThrownBy(() -> createEmployeeService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
    }

    @Test
    @DisplayName("execute() should publish EmployeeCreatedEvent on success")
    void execute_shouldPublishEmployeeCreatedEvent_onSuccess() {
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActiveEmployees()).thenReturn(0);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000004", "Jean", "Doe", storeId));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(EmployeeCreatedEvent.class);
        EmployeeCreatedEvent event = (EmployeeCreatedEvent) eventCaptor.getValue();
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.storeId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("execute() should return cleartext temp password on success")
    void execute_shouldReturnTempPasswordCleartext_onSuccess() {
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActiveEmployees()).thenReturn(0);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeResult result = createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000005", "Marc", "Eto", storeId));

        // Cleartext password returned but NOT the hash
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).doesNotStartWith("$2a$");
    }
}

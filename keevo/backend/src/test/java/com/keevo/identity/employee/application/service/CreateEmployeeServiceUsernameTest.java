package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
 * Story 19.4 — creation of a member identified by a username (Bref members): no phone, username rule, case-insensitive
 * uniqueness, concurrent-insert mapping. The phone-only path is covered by {@link CreateEmployeeServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("19.4 — CreateEmployeeService with a username")
class CreateEmployeeServiceUsernameTest {

    @Mock UserRepository userRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock EmployeeCountPort employeeCountPort;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TransactionLockPort transactionLock;

    CreateEmployeeService service;
    UUID actorId = UUID.randomUUID();
    UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test01");
        service = new CreateEmployeeService(userRepository, membershipRepository, employeeRepository,
                SubscriptionApiTestSupport.limitPort(planLimitGuard), employeeCountPort,
                SubscriptionApiTestSupport.queryPort(subscriptionRepository), tenantRepository, passwordEncoder,
                eventPublisher, transactionLock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Lenient: the conflict cases stop before the tenant lookup and the saves. */
    private void stubCreationFlow() {
        Subscription premium = mock(Subscription.class);
        lenient().when(premium.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
        lenient().when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premium));
        lenient().when(employeeCountPort.countActive(any(), any())).thenReturn(new EmployeeCountPort.ActiveCounts(0, 0));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        lenient().when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(new Tenant(UUID.randomUUID(),
                "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        lenient().when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateEmployeeCommand command(String phone, String username) {
        return new CreateEmployeeCommand(actorId, phone, "Awa", "Kamga", storeId, username);
    }

    @Test
    @DisplayName("username only → conflict-safe insert, no phone, username as typed, temp password returned once")
    void usernameOnly_creates() {
        stubCreationFlow();
        when(userRepository.findByUsername("Awa_K")).thenReturn(Optional.empty());
        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(true);

        CreateEmployeeResult result = service.execute(command(null, "  Awa_K "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insertIfAbsent(captor.capture());
        User created = captor.getValue();
        assertThat(created.getPhoneNumber()).isNull();
        assertThat(created.getUsername()).isEqualTo("Awa_K");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$12$hashedpassword");
        assertThat(result.temporaryPassword()).isNotBlank().doesNotStartWith("$2");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(membershipRepository).save(any(UserTenantMembership.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("phone and username → both checked and stored")
    void phoneAndUsername_creates() {
        stubCreationFlow();
        when(userRepository.existsByPhoneNumber("+237690000009")).thenReturn(false);
        when(userRepository.findByUsername("awa.k")).thenReturn(Optional.empty());
        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(true);

        service.execute(command("237690000009", "awa.k"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("+237690000009");
        assertThat(captor.getValue().getUsername()).isEqualTo("awa.k");
    }

    @Test
    @DisplayName("username taken in another case → 409 USERNAME_ALREADY_EXISTS before the plan lock")
    void usernameTaken() {
        User existing = User.newEmployee(null, "AWA_K", "$2b$12$x");
        when(userRepository.findByUsername("awa_k")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(command(null, "awa_k")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS.name());
        verifyNoInteractions(transactionLock, membershipRepository, employeeRepository);
        verify(userRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("concurrent insert hits uq_users_username_lower, username-only request → 409 USERNAME_ALREADY_EXISTS "
            + "even when the lookup comes back empty (no 500, never the phone error)")
    void concurrentUsernameInsert() {
        stubCreationFlow();
        // the winner's row is not visible to the pre-check nor to the follow-up lookup: no phone → still the username
        when(userRepository.findByUsername("awa_k")).thenReturn(Optional.empty());
        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command(null, "awa_k")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS.name());
        verifyNoInteractions(membershipRepository, employeeRepository, eventPublisher);
    }

    @Test
    @DisplayName("concurrent insert lost with phone + username, username row visible → 409 USERNAME_ALREADY_EXISTS")
    void concurrentInsertUsernameVisible() {
        stubCreationFlow();
        when(userRepository.existsByPhoneNumber("+237690000009")).thenReturn(false);
        when(userRepository.findByUsername("awa_k"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(User.newEmployee(null, "AWA_K", "$2b$12$x")));
        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command("+237690000009", "awa_k")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS.name());
    }

    @Test
    @DisplayName("concurrent insert lost on the phone only → 409 USER_ALREADY_EXISTS")
    void concurrentPhoneInsert() {
        stubCreationFlow();
        when(userRepository.existsByPhoneNumber("+237690000009")).thenReturn(false);
        when(userRepository.findByUsername("awa_k")).thenReturn(Optional.empty());
        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command("+237690000009", "awa_k")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"apple-x", "Google-abc", "a b", "ab", "jean@dupont", "+237690000009",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxy"})
    @DisplayName("reserved prefix or bad format → 422 VALIDATION_FAILED with the French message, nothing looked up")
    void invalidUsername(String username) {
        assertThatThrownBy(() -> service.execute(command(null, username)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
                    assertThat(d.getUserMessage()).isEqualTo(Usernames.INVALID_MESSAGE);
                });
        verifyNoInteractions(userRepository, transactionLock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("neither phone nor username (null or blank) → 422 VALIDATION_FAILED « Indiquez … »")
    void neitherIdentifier(String blank) {
        for (CreateEmployeeCommand c : new CreateEmployeeCommand[]{command(null, null), command(blank, blank)}) {
            assertThatThrownBy(() -> service.execute(c))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> {
                        DomainException d = (DomainException) e;
                        assertThat(d.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
                        assertThat(d.getUserMessage()).isEqualTo(Usernames.IDENTIFIER_REQUIRED_MESSAGE);
                    });
        }
        verifyNoInteractions(userRepository, transactionLock);
    }
}

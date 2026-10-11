package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.identity.auth.domain.model.AuthTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationService")
class RegistrationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserMembershipRepository userMembershipRepository;
    @Mock private TenantFactory tenantFactory;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SessionTokenIssuer sessionTokenIssuer;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TenantSchemaPort tenantSchemaPort; // C2 — role assignment in tenant schema

    @InjectMocks
    private RegistrationService registrationService;

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static final String PHONE   = "+237600000001";
    private static final String PASSWORD = "SecurePass123!";

    private Tenant stubbedTenant() {
        String code = "KV-ABC123";
        return new Tenant(UUID.randomUUID(), code, Tenant.schemaNameFromCode(code),
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
    }

    private User stubbedUser() {
        return User.newOwner(PHONE, "hashed_pwd");
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("register() returns RegistrationResult containing tenantCode and token")
    void register_happyPath_returnsResult() {
        // arrange
        Tenant tenant = stubbedTenant();
        User user = stubbedUser();

        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create()).thenReturn(tenant);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMembershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSession();

        // act
        RegistrationResult result = registrationService.register(
                new RegisterUserCommand(PHONE, PASSWORD, null));

        // assert
        assertThat(result.tenantCode()).isEqualTo(tenant.getCode());
        assertThat(result.token()).isEqualTo("jwt-access-token");
        // Without a refresh token the app cannot renew the session and the new
        // owner is logged out when the access token expires.
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(sessionTokenIssuer).issue(user.getId(), tenant, "OWNER");
        assertThat(result.userId()).isEqualTo(user.getId().toString());
        assertThat(result.tenantId()).isEqualTo(tenant.getId().toString());
    }

    @Test
    @DisplayName("register() publishes UserRegisteredEvent on success")
    void register_publishesDomainEvent() {
        // arrange
        Tenant tenant = stubbedTenant();
        User user = stubbedUser();

        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create()).thenReturn(tenant);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMembershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSession();

        // act
        registrationService.register(new RegisterUserCommand(PHONE, PASSWORD, null));

        // assert
        verify(eventPublisher, times(1)).publishEvent(any(UserRegisteredEvent.class));
    }

    // ── AC3: phone uniqueness ───────────────────────────────────────────────

    @Test
    @DisplayName("register() throws USER_ALREADY_EXISTS when phone is taken")
    void register_throwsWhenPhoneAlreadyExists() {
        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(true);

        DomainException ex = catchThrowableOfType(
                () -> registrationService.register(new RegisterUserCommand(PHONE, PASSWORD, null)),
                DomainException.class);

        assertThat(ex.getDomainCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verify(tenantFactory, never()).create();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("12.12 — '237…' is normalised to '+237…' before the uniqueness check")
    void register_normalisesPhoneBeforeUniqueness() {
        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(true);

        DomainException ex = catchThrowableOfType(
                () -> registrationService.register(new RegisterUserCommand("237600000001", PASSWORD, null)),
                DomainException.class);

        assertThat(ex.getDomainCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verify(userRepository).existsByPhoneNumber(PHONE);
    }

    // ── AC4: transactional rollback ─────────────────────────────────────────

    @Test
    @DisplayName("register() never saves user if tenant provisioning fails")
    void register_noUserSavedOnTenantFailure() {
        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create())
                .thenThrow(new DomainException(ErrorCode.TENANT_PROVISION_FAILED));

        catchThrowableOfType(
                () -> registrationService.register(new RegisterUserCommand(PHONE, PASSWORD, null)),
                DomainException.class);

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── C2 RED: OWNER role assigned in tenant schema (AC1) ─────────────────────

    @Test
    @DisplayName("register() assigns OWNER role in tenant schema after user is saved (AC1)")
    void register_assignsOwnerRoleInTenantSchema() {
        // ── RED: RegistrationService does not yet call tenantSchemaPort.assignOwnerRole() ──
        Tenant tenant = stubbedTenant();
        User savedUser = stubbedUser();

        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create()).thenReturn(tenant);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMembershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSession();

        registrationService.register(new RegisterUserCommand(PHONE, PASSWORD, null));

        // MUST be called with the exact schema name and the saved user's UUID
        verify(tenantSchemaPort, times(1))
                .assignOwnerRole(eq(tenant.getSchemaName()), eq(savedUser.getId()));
    }

    @Test
    @DisplayName("register() does NOT assign role if userRepository.save() throws (AC4 — no partial state)")
    void register_doesNotAssignRoleIfUserSaveFails() {
        // ── M3 RED: This failure path was not covered ──
        Tenant tenant = stubbedTenant();

        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create()).thenReturn(tenant);
        when(userRepository.save(any(User.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        catchThrowableOfType(
                () -> registrationService.register(new RegisterUserCommand(PHONE, PASSWORD, null)),
                RuntimeException.class);

        // If user was never saved, no role assignment must occur
        verify(tenantSchemaPort, never()).assignOwnerRole(anyString(), any(UUID.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private void stubSession() {
        when(sessionTokenIssuer.issue(any(), any(), anyString())).thenReturn(
                new SessionTokenIssuer.IssuedSession(
                        new AuthTokens("jwt-access-token", "refresh-token", 3600,
                                UUID.randomUUID(), "kv_abc123", "OWNER"),
                        UUID.randomUUID()));
    }

    @Test
    @DisplayName("12.12 — unprefixed phone is saved as '+237…'")
    void register_savesNormalisedPhone() {
        Tenant tenant = stubbedTenant();
        when(userRepository.existsByPhoneNumber(PHONE)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed_pwd");
        when(tenantFactory.create()).thenReturn(tenant);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMembershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSession();

        registrationService.register(new RegisterUserCommand("237600000001", PASSWORD, null));

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("12.12 — password over 72 UTF-8 bytes → VALIDATION_FAILED, BCrypt never called")
    void register_rejectsMultibytePasswordOver72Bytes() {
        String multiByte = "é1" + "é".repeat(39); // 41 chars, 82 bytes

        DomainException ex = catchThrowableOfType(
                () -> registrationService.register(new RegisterUserCommand(PHONE, multiByte, null)),
                DomainException.class);

        assertThat(ex.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        verify(passwordEncoder, never()).encode(anyString());
        verify(tenantFactory, never()).create();
    }
}

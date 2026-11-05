package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChangePasswordServiceOwnerTest — Unit tests for OWNER path (Story 8.6 AC7).
 *
 * <p>OWNER has no Employee record — should get role=OWNER token, storeId=null,
 * no EMPLOYEE_NOT_FOUND exception, and no employeeRepository mutations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChangePasswordService — OWNER path (Story 8.6 AC7)")
class ChangePasswordServiceOwnerTest {

    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock PasswordEncoder passwordEncoder;
    @Mock com.keevo.identity.auth.application.service.SessionTokenIssuer sessionTokenIssuer;
    @Mock com.keevo.identity.auth.domain.port.out.TenantRepository tenantRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.keevo.identity.auth.domain.port.out.UserMembershipRepository membershipRepository;

    @InjectMocks ChangePasswordService changePasswordService;

    UUID userId;
    User ownerUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        ownerUser = new User(userId, "+237690000002", "$2a$12$ownerHash",
                Role.OWNER, true, Instant.now());
    }

    @Test
    @DisplayName("OWNER — no EMPLOYEE_NOT_FOUND exception, returns role=OWNER token")
    void owner_noEmployeeNotFoundException_returnsOwnerToken() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(ownerUser));
        when(passwordEncoder.matches("OwnerPass1", "$2a$12$ownerHash")).thenReturn(true);
        when(passwordEncoder.encode("NewOwner1234")).thenReturn("$2a$12$newOwnerHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());
        stubIssuer("ownerAccessToken");

        ChangePasswordCommand cmd = new ChangePasswordCommand(userId, "OwnerPass1", "NewOwner1234");
        AuthTokens result = changePasswordService.execute(cmd);

        assertThat(result.accessToken()).isEqualTo("ownerAccessToken");
        assertThat(result.role()).isEqualTo("OWNER");
        assertThat(result.storeId()).isNull();
    }

    @Test
    @DisplayName("OWNER — employeeRepository.updatePasswordChangeRequired is NOT called")
    void owner_noUpdatePasswordChangeRequired() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(ownerUser));
        when(passwordEncoder.matches("OwnerPass1", "$2a$12$ownerHash")).thenReturn(true);
        when(passwordEncoder.encode("NewOwner1234")).thenReturn("$2a$12$newOwnerHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());
        stubIssuer("token");

        changePasswordService.execute(new ChangePasswordCommand(userId, "OwnerPass1", "NewOwner1234"));

        verify(employeeRepository, never()).updatePasswordChangeRequired(any(), anyBoolean());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("OWNER — refresh tokens are still revoked")
    void owner_refreshTokensRevoked() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(ownerUser));
        when(passwordEncoder.matches("OwnerPass1", "$2a$12$ownerHash")).thenReturn(true);
        when(passwordEncoder.encode("NewOwner1234")).thenReturn("$2a$12$newOwnerHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());
        stubIssuer("token");

        changePasswordService.execute(new ChangePasswordCommand(userId, "OwnerPass1", "NewOwner1234"));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(sessionTokenIssuer).issue(eq(userId), any(), eq("OWNER"), any(), any());
    }

    /** Story 16.11 — tokens are issued by SessionTokenIssuer (same path as login / refresh). */
    private void stubIssuer(String accessToken) {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.Tenant(tenantId, "KV-ABC123", "kv_abc123",
                        com.keevo.identity.api.TenantStatus.ACTIVE, com.keevo.identity.api.PlanType.FREE,
                        Instant.now())));
        // Story 19.6 — the session role comes from the OWNER membership of the current company
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.UserTenantMembership(UUID.randomUUID(), userId, tenantId,
                        "OWNER", true, Instant.now())));
        when(sessionTokenIssuer.issue(any(), any(), any(), any(), any())).thenAnswer(inv ->
                new com.keevo.identity.auth.application.service.SessionTokenIssuer.IssuedSession(
                        new AuthTokens(accessToken, "refresh", 1800, inv.getArgument(0), "kv_abc123",
                                inv.getArgument(2)), UUID.randomUUID()));
    }
}

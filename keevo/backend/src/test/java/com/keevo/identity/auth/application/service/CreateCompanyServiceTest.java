package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.CreateCompanyCommand;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreateCompanyService (Story 19.2)")
class CreateCompanyServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock JwtTokenProvider jwt;
    @Mock UserRepository users;
    @Mock OwnerTenantProvisioner provisioner;
    @Mock com.keevo.identity.auth.domain.port.out.UserMembershipRepository memberships;
    @Mock Claims claims;

    CreateCompanyService service;

    @BeforeEach
    void setUp() {
        service = new CreateCompanyService(jwt, users, memberships, provisioner);
        when(jwt.parseToken("login-token")).thenReturn(claims);
        when(jwt.extractScope(claims)).thenReturn("login_pending");
        when(jwt.extractUserId(claims)).thenReturn(USER_ID);
        when(users.lockById(USER_ID)).thenReturn(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(Role.OWNER)));
    }

    private static User user(Role role) {
        return new User(USER_ID, null, null, role, true, Instant.now(), 0, null,
                "ana@ex.com", null, null, null, Instant.now(), null);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("account without company: provisioned through the shared provisioner with the device")
    @SuppressWarnings("unchecked")
    void provisions() {
        RegistrationResult expected = new RegistrationResult("KV-ABC123", "access", USER_ID.toString(), "t", "refresh");
        when(provisioner.provision(any(), eq(DeviceInfo.of("dev-1", "Redmi")))).thenReturn(expected);

        RegistrationResult result = service.create(new CreateCompanyCommand("login-token", DeviceInfo.of("dev-1", "Redmi")));

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<Supplier<UUID>> owner = ArgumentCaptor.forClass(Supplier.class);
        verify(provisioner).provision(owner.capture(), any());
        assertThat(owner.getValue().get()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("19.6 — employee elsewhere (no OWNER membership, global role EMPLOYEE) → provisioned")
    void employeeElsewhere() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(Role.EMPLOYEE)));
        when(memberships.hasActiveOwnerMembership(USER_ID)).thenReturn(false);
        RegistrationResult expected = new RegistrationResult("KV-NEW", "access", USER_ID.toString(), "t", "refresh");
        when(provisioner.provision(any(), any())).thenReturn(expected);

        assertThat(service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE))).isSameAs(expected);
    }

    @Test
    @DisplayName("already holds an active OWNER membership → COMPANY_ALREADY_EXISTS, nothing provisioned")
    void alreadyOwner() {
        when(memberships.hasActiveOwnerMembership(USER_ID)).thenReturn(true);
        assertCode(() -> service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE)),
                ErrorCode.COMPANY_ALREADY_EXISTS);
        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("user locked but not readable (gone) → TOKEN_INVALID, never taken for a platform administrator")
    void missingUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertCode(() -> service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE)),
                ErrorCode.TOKEN_INVALID);
        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("platform administrator → COMPANY_ALREADY_EXISTS, nothing provisioned")
    void superAdmin() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(Role.SUPER_ADMIN)));
        assertCode(() -> service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE)),
                ErrorCode.COMPANY_ALREADY_EXISTS);
        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("expired token → TOKEN_EXPIRED; malformed or wrong scope → TOKEN_INVALID (as select-tenant)")
    void tokenErrors() {
        when(jwt.parseToken("expired")).thenThrow(new ExpiredJwtException(null, null, "expired"));
        when(jwt.parseToken("bad")).thenThrow(new MalformedJwtException("bad"));
        assertCode(() -> service.create(new CreateCompanyCommand("expired", DeviceInfo.NONE)), ErrorCode.TOKEN_EXPIRED);
        assertCode(() -> service.create(new CreateCompanyCommand("bad", DeviceInfo.NONE)), ErrorCode.TOKEN_INVALID);

        when(jwt.extractScope(claims)).thenReturn(null);
        assertCode(() -> service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE)),
                ErrorCode.TOKEN_INVALID);
        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("user of the token no longer exists → TOKEN_INVALID")
    void unknownUser() {
        when(users.lockById(USER_ID)).thenReturn(false);
        assertCode(() -> service.create(new CreateCompanyCommand("login-token", DeviceInfo.NONE)),
                ErrorCode.TOKEN_INVALID);
    }
}

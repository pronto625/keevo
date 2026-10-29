package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Refresh is the only way a session is renewed: the app never re-logs a user in
 * while a refresh token is valid. These tests pin rotation (one-time use), the
 * lost-response recovery, replay detection, and that renewed tokens carry the
 * same claims as a fresh login.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock SessionTokenIssuer issuer;

    private RefreshTokenService service;

    private static final String RAW = "raw-refresh-token";
    private static final String SCHEMA = "kv_abc123";
    private final UUID userId = UUID.randomUUID();
    private final Tenant tenant = new Tenant(UUID.randomUUID(), "KV-ABC123", SCHEMA,
            TenantStatus.ACTIVE, PlanType.FREE, Instant.now());

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(refreshTokenRepository, tenantRepository,
                membershipRepository, issuer);
    }

    private RefreshToken stored(boolean revoked, UUID replacedBy, Instant rotatedAt) {
        return new RefreshToken(UUID.randomUUID(), userId, SCHEMA, SessionTokenIssuer.hash(RAW),
                Instant.now().plus(300, ChronoUnit.DAYS), revoked, replacedBy, rotatedAt);
    }

    private SessionTokenIssuer.IssuedSession issued() {
        var tokens = new AuthTokens("new-access", "new-refresh", 3600, userId, SCHEMA, "EMPLOYEE");
        return new SessionTokenIssuer.IssuedSession(tokens, UUID.randomUUID());
    }

    private void activeEmployeeMembership() {
        when(tenantRepository.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
        var membership = UserTenantMembership.create(userId, tenant.getId(), "EMPLOYEE");
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId()))
                .thenReturn(Optional.of(membership));
    }

    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    @DisplayName("valid token → new session from the membership role; old token rotated to it")
    void rotatesAndIssuesFullSession() {
        var old = stored(false, null, null);
        when(refreshTokenRepository.findByHash(SessionTokenIssuer.hash(RAW))).thenReturn(Optional.of(old));
        activeEmployeeMembership();
        var session = issued();
        when(issuer.issue(userId, tenant, "EMPLOYEE")).thenReturn(session);

        AuthTokens result = service.refresh(RAW);

        assertThat(result).isSameAs(session.tokens());
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(old.id());
        assertThat(saved.getValue().revoked()).isTrue();
        assertThat(saved.getValue().replacedById()).isEqualTo(session.refreshTokenId());
    }

    @Test
    @DisplayName("unknown token → REFRESH_TOKEN_INVALID")
    void unknownToken() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());
        assertInvalid(() -> service.refresh(RAW));
    }

    @Test
    @DisplayName("expired token → REFRESH_TOKEN_INVALID")
    void expiredToken() {
        var expired = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, SessionTokenIssuer.hash(RAW),
                Instant.now().minus(1, ChronoUnit.DAYS), false);
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(expired));
        assertInvalid(() -> service.refresh(RAW));
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("token revoked outright (logout, password change) → REFRESH_TOKEN_INVALID")
    void revokedOutright() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(stored(true, null, null)));
        assertInvalid(() -> service.refresh(RAW));
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("membership deactivated → REFRESH_TOKEN_INVALID")
    void inactiveMembership() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(stored(false, null, null)));
        when(tenantRepository.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
        var inactive = new UserTenantMembership(UUID.randomUUID(), userId, tenant.getId(),
                "EMPLOYEE", false, Instant.now());
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId()))
                .thenReturn(Optional.of(inactive));

        assertInvalid(() -> service.refresh(RAW));
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("deleted tenant → REFRESH_TOKEN_INVALID")
    void deletedTenant() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(stored(false, null, null)));
        var deleted = new Tenant(tenant.getId(), "KV-ABC123", SCHEMA, TenantStatus.DELETED,
                PlanType.FREE, Instant.now());
        when(tenantRepository.findBySchemaName(SCHEMA)).thenReturn(Optional.of(deleted));

        assertInvalid(() -> service.refresh(RAW));
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("lost response: rotated token replayed while its successor was never used → recovered")
    void lostResponseRecovery() {
        var successor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), false);
        var old = stored(true, successor.id(), Instant.now().minus(2, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(successor.id())).thenReturn(Optional.of(successor));
        activeEmployeeMembership();
        var session = issued();
        when(issuer.issue(userId, tenant, "EMPLOYEE")).thenReturn(session);

        assertThat(service.refresh(RAW)).isSameAs(session.tokens());

        // Only one token of the chain stays alive: the unused successor is superseded,
        // and the window keeps counting from the first exchange.
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        var supersededSuccessor = saved.getAllValues().get(0);
        assertThat(supersededSuccessor.id()).isEqualTo(successor.id());
        assertThat(supersededSuccessor.isSuperseded()).isTrue();
        var repointed = saved.getAllValues().get(1);
        assertThat(repointed.id()).isEqualTo(old.id());
        assertThat(repointed.replacedById()).isEqualTo(session.refreshTokenId());
        assertThat(repointed.rotatedAt()).isEqualTo(old.rotatedAt());
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("superseded token presented (someone else recovered past it) → all sessions revoked")
    void supersededTokenPresented() {
        var superseded = stored(true, null, Instant.now().minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(superseded));

        assertInvalid(() -> service.refresh(RAW));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("replay of a token whose successor was superseded → all sessions revoked")
    void successorSupersededMeansTwoHolders() {
        var supersededSuccessor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), true, null, Instant.now());
        var old = stored(true, supersededSuccessor.id(), Instant.now().minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(supersededSuccessor.id()))
                .thenReturn(Optional.of(supersededSuccessor));

        assertInvalid(() -> service.refresh(RAW));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("replay of a token whose successor was signed out → refused, other sessions kept")
    void successorRevokedOutrightAfterLogout() {
        var signedOutSuccessor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), true);
        var old = stored(true, signedOutSuccessor.id(), Instant.now().minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(signedOutSuccessor.id()))
                .thenReturn(Optional.of(signedOutSuccessor));

        assertInvalid(() -> service.refresh(RAW));

        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("refresh keeps the replay revocation when it throws (noRollbackFor DomainException)")
    void refreshKeepsRevocationOnDomainException() throws NoSuchMethodException {
        Transactional tx = RefreshTokenService.class.getMethod("refresh", String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(DomainException.class);
    }

    @Test
    @DisplayName("replay of a token whose successor was already used → all sessions revoked")
    void replayDetected() {
        var usedSuccessor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), true, UUID.randomUUID(), Instant.now());
        var old = stored(true, usedSuccessor.id(), Instant.now().minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(usedSuccessor.id())).thenReturn(Optional.of(usedSuccessor));

        assertInvalid(() -> service.refresh(RAW));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("rotated token replayed after the recovery window → REFRESH_TOKEN_INVALID")
    void recoveryWindowElapsed() {
        var successor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), false);
        var old = stored(true, successor.id(),
                Instant.now().minus(RefreshTokenService.LOST_RESPONSE_WINDOW).minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(successor.id())).thenReturn(Optional.of(successor));

        assertInvalid(() -> service.refresh(RAW));
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("logout revokes the presented refresh token outright")
    void logoutRevokesToken() {
        var token = stored(false, null, null);
        when(refreshTokenRepository.findByHash(SessionTokenIssuer.hash(RAW))).thenReturn(Optional.of(token));

        service.revoke(RAW);

        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(token.id());
        assertThat(saved.getValue().revoked()).isTrue();
        assertThat(saved.getValue().isRotated()).isFalse();
    }

    @Test
    @DisplayName("logout with a token whose renewal answer was lost revokes the live successor")
    void logoutFollowsRotationChain() {
        var successor = new RefreshToken(UUID.randomUUID(), userId, SCHEMA, "succ-hash",
                Instant.now().plus(300, ChronoUnit.DAYS), false);
        var old = stored(true, successor.id(), Instant.now().minus(1, ChronoUnit.HOURS));
        when(refreshTokenRepository.findByHash(SessionTokenIssuer.hash(RAW))).thenReturn(Optional.of(old));
        when(refreshTokenRepository.findById(successor.id())).thenReturn(Optional.of(successor));

        service.revoke(RAW);

        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(successor.id());
        assertThat(saved.getValue().revoked()).isTrue();
        assertThat(saved.getValue().isRotated()).isFalse();
        assertThat(saved.getValue().isSuperseded()).isFalse();
    }

    @Test
    @DisplayName("logout with an unknown token is a no-op (idempotent, no oracle)")
    void logoutUnknownToken() {
        when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.revoke(RAW)).doesNotThrowAnyException();
        verify(refreshTokenRepository, never()).save(any());
    }
}

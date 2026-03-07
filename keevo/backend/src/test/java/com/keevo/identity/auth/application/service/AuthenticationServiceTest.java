package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationServiceTest — TDD tests for Story 1.7 authentication step 1.
 *
 * <p>Step 1 behavior: phone+password → lockout check → loginToken (5min, scope=login_pending)
 * + membership list. Does NOT issue access/refresh tokens (that is SelectTenantService).
 *
 * <p>Test Validity Principle:
 * - {@link JwtTokenProvider} is a REAL instance — NOT mocked (core security logic).
 * - Only true system boundaries are mocked: {@link UserRepository}, {@link PasswordEncoder}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService (step 1 — two-step login)")
class AuthenticationServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private JwtTokenProvider realJwtTokenProvider;
    private AuthenticationService authService;

    @BeforeEach
    void setUp() throws Exception {
        RSAPrivateKey privateKey = loadTestPrivateKey();
        RSAPublicKey  publicKey  = loadTestPublicKey();
        JwtProperties props = new JwtProperties(24, 30);
        realJwtTokenProvider = new JwtTokenProvider(privateKey, publicKey, props);
        authService = new AuthenticationService(userRepository, passwordEncoder, realJwtTokenProvider);
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate returns loginToken with scope=login_pending and memberships list")
    void should_return_login_session_result_with_login_token_and_memberships() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "+22670000001", "$2a$12$hash", 0, null);
        List<UserMembershipInfo> memberships = List.of(
                new UserMembershipInfo("KV-ABC123", "My Shop", "OWNER", "kv_abc123"));

        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SecurePass1!", user.getPasswordHash())).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(memberships);

        LoginSessionResult result = authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "SecurePass1!", null));

        // loginToken MUST be a real parseable RS256 JWT with scope=login_pending
        Claims claims = realJwtTokenProvider.parseToken(result.loginToken());
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(realJwtTokenProvider.extractScope(claims)).isEqualTo("login_pending");

        // memberships come from repository
        assertThat(result.memberships()).hasSize(1);
        assertThat(result.memberships().get(0).tenantCode()).isEqualTo("KV-ABC123");
        assertThat(result.memberships().get(0).role()).isEqualTo("OWNER");

        // failedAttempts reset to 0 on success
        verify(userRepository).save(argThat(u -> u.failedAttempts() == 0 && u.lockedUntil() == null));
    }

    @Test
    @DisplayName("authenticate does NOT publish events and does NOT save refresh tokens (step 1 only)")
    void should_not_issue_access_token_or_refresh_token_in_step1() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "+22670000001", "$2a$12$hash", 0, null);
        when(userRepository.findByPhoneNumber(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of());

        LoginSessionResult result = authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "pass", null));

        // loginToken must exist but must NOT be usable as an access token
        assertThat(result.loginToken()).isNotBlank();
        Claims claims = realJwtTokenProvider.parseToken(result.loginToken());
        assertThat(realJwtTokenProvider.extractScope(claims)).isEqualTo("login_pending");

        // Only 1 userRepository interaction for save (reset lockout) + 1 for memberships
        verify(userRepository, times(1)).save(any());
        verify(userRepository, times(1)).findMembershipsWithTenantInfo(userId);
    }

    // ── Invalid credentials ────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate with wrong password throws INVALID_CREDENTIALS")
    void should_throw_invalid_credentials_on_wrong_password() {
        User user = buildUser(UUID.randomUUID(), "+22670000001", "$2a$12$hash", 0, null);
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", user.getPasswordHash())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "WrongPass", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("authenticate with unknown phone throws INVALID_CREDENTIALS (not USER_NOT_FOUND)")
    void should_throw_invalid_credentials_for_unknown_phone() {
        when(userRepository.findByPhoneNumber("+00000000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(
                new AuthenticateUserCommand("+00000000000", "AnyPass", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    // ── Account lockout ────────────────────────────────────────────────────

    @Test
    @DisplayName("5th consecutive failed attempt locks account for 15 minutes")
    void should_lock_account_after_5_failed_attempts() {
        User user = buildUser(UUID.randomUUID(), "+22670000001", "$2a$12$hash", 4, null);
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "WrongPass", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(userRepository).save(argThat(u ->
                u.failedAttempts() == 5 && u.lockedUntil() != null));
    }

    @Test
    @DisplayName("locked account immediately throws ACCOUNT_LOCKED without checking password")
    void should_reject_locked_account_immediately() {
        User lockedUser = buildUser(UUID.randomUUID(), "+22670000001", "$2a$12$hash",
                5, Instant.now().plus(10, ChronoUnit.MINUTES));
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(lockedUser));

        assertThatThrownBy(() -> authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "AnyPass", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_LOCKED));

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("expired lockout (lockedUntil in past) allows login attempt")
    void should_allow_login_when_lockout_expired() {
        UUID userId = UUID.randomUUID();
        User expiredLockUser = buildUser(userId, "+22670000001", "$2a$12$hash",
                5, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(expiredLockUser));
        when(passwordEncoder.matches("CorrectPass", expiredLockUser.getPasswordHash())).thenReturn(true);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of());

        assertThatCode(() -> authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "CorrectPass", null)))
                .doesNotThrowAnyException();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private User buildUser(UUID id, String phone, String pwdHash,
                            int failedAttempts, Instant lockedUntil) {
        return new User(id, phone, pwdHash, Role.OWNER, true, Instant.now(),
                failedAttempts, lockedUntil);
    }

    private RSAPrivateKey loadTestPrivateKey() throws Exception {
        var stream = getClass().getResourceAsStream("/keys/private_key.pem");
        assertThat(stream).as("Test private key must exist at src/test/resources/keys/private_key.pem").isNotNull();
        String pem = new String(stream.readAllBytes())
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private RSAPublicKey loadTestPublicKey() throws Exception {
        var stream = getClass().getResourceAsStream("/keys/public_key.pem");
        assertThat(stream).as("Test public key must exist at src/test/resources/keys/public_key.pem").isNotNull();
        String pem = new String(stream.readAllBytes())
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
    }
}

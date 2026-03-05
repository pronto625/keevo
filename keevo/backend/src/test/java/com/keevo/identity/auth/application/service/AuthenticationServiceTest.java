package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationServiceTest — TDD tests for authentication and account lockout logic.
 *
 * <p>Test Validity (Test Validity Principle):
 * - {@link JwtTokenProvider} is a REAL instance loaded with test RSA keys.
 *   It is NOT mocked because it is core security logic, not a system boundary.
 *   If {@code generateAccessToken()} is deleted or produces invalid output, these tests fail.
 * - Only true system boundaries are mocked: {@link UserRepository}, {@link TenantRepository},
 *   {@link RefreshTokenRepository} (persistence), {@link PasswordEncoder} (Spring Security),
 *   and {@link ApplicationEventPublisher} (framework event bus).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService")
class AuthenticationServiceTest {

    @Mock UserRepository userRepository;
    @Mock TenantRepository tenantRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher eventPublisher;

    // Real JwtTokenProvider — NOT mocked. Core security logic must exercise real RSA signing.
    private JwtTokenProvider realJwtTokenProvider;
    private JwtProperties testJwtProperties;
    private AuthenticationService authService;

    @BeforeEach
    void setUp() throws Exception {
        RSAPrivateKey privateKey = loadTestPrivateKey();
        RSAPublicKey publicKey   = loadTestPublicKey();
        testJwtProperties = new JwtProperties(24, 30);
        realJwtTokenProvider = new JwtTokenProvider(privateKey, publicKey, testJwtProperties);
        authService = new AuthenticationService(
                userRepository, tenantRepository, refreshTokenRepository,
                passwordEncoder, realJwtTokenProvider, testJwtProperties, eventPublisher);
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate with valid credentials returns real RS256 JWT with correct claims")
    void should_authenticate_valid_credentials_and_return_tokens() {
        UUID tenantUuid = UUID.randomUUID();
        User user = buildUser("+22670000001", "$2a$12$hashedpassword", 0, null, tenantUuid);
        Tenant tenant = buildTenant(tenantUuid, "KV-ABC123", "kv_abc123");
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SecurePass1!", user.getPasswordHash())).thenReturn(true);
        when(tenantRepository.findById(tenantUuid)).thenReturn(Optional.of(tenant));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthTokens result = authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "SecurePass1!", null));

        // The access token MUST be a real parseable RS256 JWT — not a stub string
        Claims claims = realJwtTokenProvider.parseToken(result.accessToken());
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo("kv_abc123");
        assertThat(claims.get("role", String.class)).isEqualTo("OWNER");
        assertThat(claims.getExpiration()).isAfter(java.util.Date.from(Instant.now()));

        // Refresh token must be an opaque base64url string (non-empty, URL-safe)
        assertThat(result.refreshToken()).hasSizeGreaterThanOrEqualTo(80);
        assertThat(result.refreshToken()).doesNotContain("+", "/", "=");

        assertThat(result.expiresIn()).isEqualTo(86400L);

        verify(refreshTokenRepository).save(any(RefreshToken.class));
        // Must reset failedAttempts to 0 on success
        verify(userRepository).save(argThat(u -> u.failedAttempts() == 0 && u.lockedUntil() == null));
    }

    @Test
    @DisplayName("authenticate publishes UserAuthenticatedEvent on success")
    void should_publish_authenticated_event_on_success() {
        UUID tenantUuid = UUID.randomUUID();
        User user = buildUser("+22670000001", "$2a$12$hash", 0, null, tenantUuid);
        Tenant tenant = buildTenant(tenantUuid, "KV-ABC123", "kv_abc123");
        when(userRepository.findByPhoneNumber(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(tenantRepository.findById(tenantUuid)).thenReturn(Optional.of(tenant));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.authenticate(new AuthenticateUserCommand("+22670000001", "pass", null));

        verify(eventPublisher).publishEvent((Object) any());
    }

    // ── Invalid credentials ────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate with wrong password throws INVALID_CREDENTIALS")
    void should_throw_invalid_credentials_on_wrong_password() {
        User user = buildUser("+22670000001", "$2a$12$hash", 0, null, UUID.randomUUID());
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
        User user = buildUser("+22670000001", "$2a$12$hash", 4, null, UUID.randomUUID());
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
        User lockedUser = buildUser("+22670000001", "$2a$12$hash", 5,
                Instant.now().plus(10, ChronoUnit.MINUTES), UUID.randomUUID());
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
        UUID tenantUuid = UUID.randomUUID();
        User expiredLockUser = buildUser("+22670000001", "$2a$12$hash", 5,
                Instant.now().minus(1, ChronoUnit.MINUTES), tenantUuid);
        Tenant tenant = buildTenant(tenantUuid, "KV-TEST", "kv_test");
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(Optional.of(expiredLockUser));
        when(passwordEncoder.matches("CorrectPass", expiredLockUser.getPasswordHash())).thenReturn(true);
        when(tenantRepository.findById(tenantUuid)).thenReturn(Optional.of(tenant));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> authService.authenticate(
                new AuthenticateUserCommand("+22670000001", "CorrectPass", null)))
                .doesNotThrowAnyException();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private User buildUser(String phone, String pwdHash, int failedAttempts,
                            Instant lockedUntil, UUID tenantId) {
        return new User(
                UUID.randomUUID(), phone, pwdHash,
                Role.OWNER, tenantId,
                true, Instant.now(),
                failedAttempts, lockedUntil
        );
    }

    private Tenant buildTenant(UUID id, String code, String schemaName) {
        return new Tenant(id, code, schemaName,
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
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


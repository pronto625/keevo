package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SelectTenantServiceTest — TDD tests for step 2 of two-step login (Story 1.7).
 *
 * <p>Uses a REAL JwtTokenProvider to generate and parse loginTokens (true RS256 signing).
 * Mocks all persistence ports.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SelectTenantService")
class SelectTenantServiceTest {

    @Mock TenantRepository        tenantRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock RefreshTokenRepository  refreshTokenRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private JwtTokenProvider realJwtProvider;
    private SelectTenantService selectTenantService;

    private static final String TENANT_CODE  = "KV-ABC123";
    private static final String SCHEMA_NAME  = "kv_abc123";

    @BeforeEach
    void setUp() throws Exception {
        RSAPrivateKey privateKey = loadTestPrivateKey();
        RSAPublicKey  publicKey  = loadTestPublicKey();
        JwtProperties props = new JwtProperties(24, 30);
        realJwtProvider = new JwtTokenProvider(privateKey, publicKey, props);
        selectTenantService = new SelectTenantService(
                tenantRepository, membershipRepository,
                refreshTokenRepository, realJwtProvider, props, eventPublisher, null);
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("select() with valid loginToken + valid tenantCode returns full AuthTokens")
    void should_return_auth_tokens_for_valid_login_token_and_tenant() {
        UUID userId     = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();

        String loginToken = realJwtProvider.generateLoginToken(userId);

        Tenant tenant = buildTenant(tenantUuid, TENANT_CODE, SCHEMA_NAME);
        UserTenantMembership membership = UserTenantMembership.create(userId, tenantUuid, "OWNER");

        when(tenantRepository.findByCode(TENANT_CODE)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantUuid))
                .thenReturn(Optional.of(membership));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthTokens result = selectTenantService.select(
                new SelectTenantCommand(loginToken, TENANT_CODE));

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.tenantId()).isEqualTo(SCHEMA_NAME);
        assertThat(result.role()).isEqualTo("OWNER");
        assertThat(result.userId()).isEqualTo(userId);
        // access token must be parseable with correct claims
        var claims = realJwtProvider.parseToken(result.accessToken());
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(SCHEMA_NAME);
        assertThat(claims.get("role", String.class)).isEqualTo("OWNER");
        // scope must NOT be login_pending in an access token
        assertThat(realJwtProvider.extractScope(claims)).isNull();
    }

    @Test
    @DisplayName("select() with access token used as loginToken throws TOKEN_INVALID")
    void should_throw_token_invalid_when_access_token_used_as_login_token() {
        UUID userId = UUID.randomUUID();
        // this is an access token, NOT a loginToken
        String accessToken = realJwtProvider.generateAccessToken(userId, SCHEMA_NAME, "OWNER");

        assertThatThrownBy(() -> selectTenantService.select(
                new SelectTenantCommand(accessToken, TENANT_CODE)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    @Test
    @DisplayName("select() with expired loginToken throws TOKEN_EXPIRED")
    void should_throw_token_expired_for_expired_login_token() throws Exception {
        RSAPrivateKey privateKey = loadTestPrivateKey();
        // Build an already-expired JWT with scope=login_pending using raw JJWT builder
        Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
        String expiredLoginToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(5, ChronoUnit.MINUTES)))
                .expiration(Date.from(past)) // expired 10 minutes ago
                .claim("scope", "login_pending")
                .signWith(privateKey)
                .compact();

        assertThatThrownBy(() -> selectTenantService.select(
                new SelectTenantCommand(expiredLoginToken, TENANT_CODE)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    @DisplayName("select() with unknown tenantCode throws TENANT_NOT_FOUND")
    void should_throw_tenant_not_found_for_unknown_code() {
        UUID userId = UUID.randomUUID();
        String loginToken = realJwtProvider.generateLoginToken(userId);

        when(tenantRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> selectTenantService.select(
                new SelectTenantCommand(loginToken, "UNKNOWN")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TENANT_NOT_FOUND));
    }

    @Test
    @DisplayName("select() when user has no membership for the tenant throws MEMBERSHIP_NOT_FOUND")
    void should_throw_membership_not_found_when_no_membership() {
        UUID userId     = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        String loginToken = realJwtProvider.generateLoginToken(userId);
        Tenant tenant = buildTenant(tenantUuid, TENANT_CODE, SCHEMA_NAME);

        when(tenantRepository.findByCode(TENANT_CODE)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantUuid))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> selectTenantService.select(
                new SelectTenantCommand(loginToken, TENANT_CODE)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    @Test
    @DisplayName("select() with inactive membership throws MEMBERSHIP_NOT_FOUND")
    void should_throw_when_membership_inactive() {
        UUID userId     = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        String loginToken = realJwtProvider.generateLoginToken(userId);
        Tenant tenant = buildTenant(tenantUuid, TENANT_CODE, SCHEMA_NAME);
        // Inactive membership
        UserTenantMembership inactiveMembership = new UserTenantMembership(
                UUID.randomUUID(), userId, tenantUuid, "OWNER", false, Instant.now());

        when(tenantRepository.findByCode(TENANT_CODE)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantUuid))
                .thenReturn(Optional.of(inactiveMembership));

        assertThatThrownBy(() -> selectTenantService.select(
                new SelectTenantCommand(loginToken, TENANT_CODE)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    @Test
    @DisplayName("select() persists a refresh token and publishes UserAuthenticatedEvent")
    void should_persist_refresh_token_and_publish_event() {
        UUID userId     = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        String loginToken = realJwtProvider.generateLoginToken(userId);
        Tenant tenant = buildTenant(tenantUuid, TENANT_CODE, SCHEMA_NAME);
        UserTenantMembership membership = UserTenantMembership.create(userId, tenantUuid, "OWNER");

        when(tenantRepository.findByCode(TENANT_CODE)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantUuid))
                .thenReturn(Optional.of(membership));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        selectTenantService.select(new SelectTenantCommand(loginToken, TENANT_CODE));

        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(eventPublisher).publishEvent((Object) any());
    }

    // ── Multi-tenant scenario (AC12) ────────────────────────────────────────

    @Test
    @DisplayName("select() for 2nd tenant returns JWT scoped to that tenant (AC12)")
    void should_return_jwt_scoped_to_selected_tenant() {
        UUID userId      = UUID.randomUUID();
        UUID tenantB_Id  = UUID.randomUUID();
        String tokenB    = "KV-XYZ789";
        String schemaB   = "kv_xyz789";

        String loginToken = realJwtProvider.generateLoginToken(userId);
        Tenant tenantB = buildTenant(tenantB_Id, tokenB, schemaB);
        UserTenantMembership membershipB = UserTenantMembership.create(userId, tenantB_Id, "OWNER");

        when(tenantRepository.findByCode(tokenB)).thenReturn(Optional.of(tenantB));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantB_Id))
                .thenReturn(Optional.of(membershipB));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthTokens result = selectTenantService.select(
                new SelectTenantCommand(loginToken, tokenB));

        assertThat(result.tenantId()).isEqualTo(schemaB);
        var claims = realJwtProvider.parseToken(result.accessToken());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(schemaB);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Tenant buildTenant(UUID id, String code, String schemaName) {
        return new Tenant(id, code, schemaName, TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
    }

    private RSAPrivateKey loadTestPrivateKey() throws Exception {
        var stream = getClass().getResourceAsStream("/keys/private_key.pem");
        assertThat(stream).as("Test private key must exist").isNotNull();
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
        assertThat(stream).as("Test public key must exist").isNotNull();
        String pem = new String(stream.readAllBytes())
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
    }
}

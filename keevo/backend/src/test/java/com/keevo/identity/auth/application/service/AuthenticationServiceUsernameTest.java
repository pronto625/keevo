package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 19.4 — username + password login (Bref members): the lookup differs, the rest of the two-step login is the
 * phone flow. A REAL {@link BCryptPasswordEncoder} (spied) checks hashes produced outside Java: {@code $2b$12$} as
 * Node's bcrypt (Bref) writes them, {@code $2y$} as PHP/htpasswd — never rehashed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("19.4 — AuthenticationService, username path")
class AuthenticationServiceUsernameTest {

    /** bcrypt("secret6", cost 12, prefix $2b$) — produced by the reference bcrypt library (same as Node's). */
    static final String NODE_2B_12_SECRET6 = "$2b$12$uJP5XlDUmIGkKXbs6pUv5uyfefvSMoVTKAXjIvXJy2R4.i11Crz0C";
    /** bcrypt("secret6", cost 10, prefix $2y$) — produced by htpasswd -B. */
    static final String HTPASSWD_2Y_10_SECRET6 = "$2y$10$isWGKvVqsdEdt.5u2F24NeGbm54y6iu/BkxByZpa5xM5DGj7jLx7.";

    @Mock UserRepository userRepository;

    BCryptPasswordEncoder encoder;
    JwtTokenProvider jwt;
    AuthenticationService service;
    MutableClock clock;
    LoginRateLimiter limiter;

    /** Clock moved by the tests (the limiter's Caffeine ticker). */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final String INVALID_USERNAME_MESSAGE = "Identifiant ou mot de passe incorrect";

    @BeforeEach
    void setUp() {
        encoder = spy(new BCryptPasswordEncoder(4));
        jwt = TestJwt.provider(new JwtProperties(24, 30));
        clock = new MutableClock();
        limiter = new LoginRateLimiter(clock, 50);
        service = new AuthenticationService(userRepository, encoder, jwt, limiter);
        clearInvocations(encoder); // the dummy hash encoded by the constructor
    }

    private static void usernameMessage(Throwable e) {
        assertThat(((DomainException) e).getUserMessage()).isEqualTo(INVALID_USERNAME_MESSAGE);
    }

    private static User member(String username, String hash, int failures, Instant lockedUntil) {
        return new User(UUID.randomUUID(), null, hash, Role.EMPLOYEE, true, Instant.now(), failures, lockedUntil,
                null, username, null, null, null, null);
    }

    private static void code(Throwable e, ErrorCode expected) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).getDomainCode()).isEqualTo(expected.name());
    }

    @Test
    @DisplayName("Jean.Dupont logs in as jean.dupont with a Node $2b$12$ hash → loginToken + memberships, hash unchanged")
    void usernameLoginWithBrefHash() {
        User user = member("jean.dupont", NODE_2B_12_SECRET6, 2, null);
        when(userRepository.findByUsername("Jean.Dupont")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(user.getId())).thenReturn(
                List.of(new UserMembershipInfo("KV-ABC123", "Boutique", "EMPLOYEE", "kv_abc123")));

        LoginSessionResult result = service.authenticate(AuthenticateUserCommand.byUsername("Jean.Dupont", "secret6"));

        assertThat(jwt.extractScope(jwt.parseToken(result.loginToken()))).isEqualTo("login_pending");
        assertThat(jwt.parseToken(result.loginToken()).getSubject()).isEqualTo(user.getId().toString());
        assertThat(result.memberships()).extracting(UserMembershipInfo::tenantCode).containsExactly("KV-ABC123");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(NODE_2B_12_SECRET6); // no rehash
        assertThat(saved.getValue().failedAttempts()).isZero();
        verify(userRepository, never()).findByPhoneNumber(any());
        verify(encoder, never()).encode(any());
    }

    @Test
    @DisplayName("$2y$ hashes (any cost) are accepted as is")
    void dollar2yHash() {
        User user = member("awa_k", HTPASSWD_2Y_10_SECRET6, 0, null);
        when(userRepository.findByUsername("awa_k")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(user.getId())).thenReturn(List.of());

        assertThat(service.authenticate(AuthenticateUserCommand.byUsername("awa_k", "secret6")).loginToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("unknown username → one dummy bcrypt (same timing as a wrong password), then INVALID_CREDENTIALS")
    void unknownUsername() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("ghost", "secret6")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS))
                .satisfies(e -> assertThat(((DomainException) e).getUserMessage())
                        .isEqualTo("Identifiant ou mot de passe incorrect"));
        verify(encoder, times(1)).matches(eq("secret6"), anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("wrong password ×5 → 4 × INVALID_CREDENTIALS, the 5th locks; then ACCOUNT_LOCKED")
    void lockoutAfterFiveFailures() {
        User[] current = {member("awa_k", NODE_2B_12_SECRET6, 0, null)};
        when(userRepository.findByUsername("awa_k")).thenAnswer(inv -> Optional.of(current[0]));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> current[0] = inv.getArgument(0));

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("awa_k", "wrong!")))
                    .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS))
                    .satisfies(AuthenticationServiceUsernameTest::usernameMessage);
            assertThat(current[0].failedAttempts()).isEqualTo(i);
        }
        assertThat(current[0].lockedUntil()).isAfter(Instant.now());

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("awa_k", "secret6")))
                .satisfies(e -> code(e, ErrorCode.ACCOUNT_LOCKED));
    }

    @Test
    @DisplayName("6-character password on the username path → 200")
    void sixCharPassword() {
        User user = member("awa_k", NODE_2B_12_SECRET6, 0, null);
        when(userRepository.findByUsername("AWA_K")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(user.getId())).thenReturn(List.of());

        assertThat(service.authenticate(AuthenticateUserCommand.byUsername("AWA_K", "secret6")).loginToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("account without password → INVALID_CREDENTIALS, not counted")
    void noPassword() {
        User user = member("awa_otp", null, 0, null);
        when(userRepository.findByUsername("awa_otp")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("awa_otp", "x")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS))
                .satisfies(AuthenticationServiceUsernameTest::usernameMessage);
        verify(userRepository, never()).save(any());
        verify(encoder, times(1)).matches(eq("x"), anyString()); // same timing as a wrong password
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-ab12cd34", "Apple-0123abcd"})
    @DisplayName("a social account's technical username is refused like an unknown one (dummy bcrypt), even with a hash")
    void technicalUsernameRefused(String technical) {
        User user = member(technical, NODE_2B_12_SECRET6, 0, null);
        when(userRepository.findByUsername(technical)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername(technical, "secret6")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS))
                .satisfies(AuthenticationServiceUsernameTest::usernameMessage);
        verify(encoder, times(1)).matches(eq("secret6"), anyString());
        verify(encoder, never()).matches(any(), eq(NODE_2B_12_SECRET6));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findMembershipsWithTenantInfo(any());
    }

    @Test
    @DisplayName("a SUPER_ADMIN is never signed in by username, even with the right password; nothing counted")
    void superAdminRefused() {
        User admin = new User(UUID.randomUUID(), "+237600000000", NODE_2B_12_SECRET6, Role.SUPER_ADMIN, true,
                Instant.now(), 0, null, null, "admin", null, null, null, null);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("admin", "secret6")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findMembershipsWithTenantInfo(any());
        verify(encoder, times(1)).matches(eq("secret6"), anyString());
        verify(encoder, never()).matches(any(), eq(NODE_2B_12_SECRET6));
    }

    @Test
    @DisplayName("per-IP brake: 50 failed logins (both paths) → the 51st is 429 before the lookup; other IP unaffected")
    void ipLimiter() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        for (int i = 0; i < 50; i++) {
            AuthenticateUserCommand c = i % 2 == 0
                    ? AuthenticateUserCommand.byUsername("user" + i, "secret6", "10.0.0.1")
                    : AuthenticateUserCommand.byPhone("+23769000" + String.format("%04d", i), "secret66", "10.0.0.1");
            assertThatThrownBy(() -> service.authenticate(c)).satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS));
        }
        clearInvocations(userRepository);

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("user99", "secret6", "10.0.0.1")))
                .satisfies(e -> code(e, ErrorCode.RATE_LIMIT_EXCEEDED));
        verifyNoInteractions(userRepository);

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("user99", "secret6", "10.0.0.2")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS));

        clock.now = clock.now.plus(Duration.ofMinutes(61)); // window over
        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername("user99", "secret6", "10.0.0.1")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("per-IP brake: successful logins are never counted")
    void ipLimiterIgnoresSuccess() {
        User user = member("awa_k", new BCryptPasswordEncoder(4).encode("secret6"), 0, null); // cheap cost for 60 logins
        when(userRepository.findByUsername("awa_k")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findMembershipsWithTenantInfo(user.getId())).thenReturn(List.of());

        for (int i = 0; i < 60; i++) {
            limiter.checkAllowed("10.0.0.3");
            service.authenticate(AuthenticateUserCommand.byUsername("awa_k", "secret6", "10.0.0.3"));
        }
        limiter.checkAllowed("10.0.0.3"); // still allowed
    }

    @ParameterizedTest
    @ValueSource(strings = {"237690000001", "690000001"})
    @DisplayName("an all-digit username is only looked up as a username, never as a phone")
    void allDigitUsernameNeverPhone(String digits) {
        when(userRepository.findByUsername(digits)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(AuthenticateUserCommand.byUsername(digits, "secret6")))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS));
        verify(userRepository, never()).findByPhoneNumber(any());
    }

    @Test
    @DisplayName("phone path unchanged: looked up as a phone only, default French message")
    void phonePathUnchanged() {
        when(userRepository.findByPhoneNumber("+237690000001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(new AuthenticateUserCommand("237690000001", "secret66", null)))
                .satisfies(e -> code(e, ErrorCode.INVALID_CREDENTIALS))
                .satisfies(e -> assertThat(((DomainException) e).getUserMessage()).isNull());
        verify(userRepository, never()).findByUsername(any());
        verify(encoder, never()).matches(any(), any());
    }
}

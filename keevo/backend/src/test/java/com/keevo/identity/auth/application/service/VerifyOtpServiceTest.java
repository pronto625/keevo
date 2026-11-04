package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.OtpChallenge;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.VerifyOtpCommand;
import com.keevo.identity.auth.domain.port.out.OtpChallengeRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VerifyOtpService (Story 19.2)")
class VerifyOtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final String PHONE = "+237699001122";
    private static final String EMAIL = "ana@ex.com";
    private static final UUID CHALLENGE_ID = UUID.randomUUID();

    @Mock OtpChallengeRepository repo;
    @Mock UserRepository users;
    @Mock PasswordEncoder encoder;
    @Mock JwtTokenProvider jwt;

    VerifyOtpService service;

    @BeforeEach
    void setUp() {
        MutableTestClock clock = new MutableTestClock(NOW);
        service = new VerifyOtpService(new OtpSettings("237", "review@brefpoint.test", "246810"),
                new OtpRateLimiter(clock, 3, 5, 10, 30), repo, users, encoder, jwt, clock);
        when(jwt.generateLoginToken(any())).thenReturn("login-token");
        when(encoder.matches("123456", "hash")).thenReturn(true);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.lockLatestActive(anyString(), eq(NOW)))
                .thenReturn(Optional.of(new OtpChallenge(CHALLENGE_ID, "hash", 0)));
    }

    private static User owner(String phone, String email) {
        return new User(UUID.randomUUID(), phone, "pwd-hash", Role.OWNER, true, NOW.minus(Duration.ofDays(30)),
                0, null, email, null, null, null, null, null);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("right code, existing owner: challenge consumed, phone_verified_at set, loginToken + memberships")
    void existingOwner() {
        User owner = owner(PHONE, null);
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(owner));
        var membership = new UserMembershipInfo("KV-ABC123", "Boutique", "OWNER", "kv_abc123");
        when(users.findMembershipsWithTenantInfo(owner.getId())).thenReturn(List.of(membership));

        LoginSessionResult result = service.verify(new VerifyOtpCommand("+237 699 00 11 22", "123456"));

        assertThat(result.loginToken()).isEqualTo("login-token");
        assertThat(result.memberships()).containsExactly(membership);
        verify(repo).consume(CHALLENGE_ID, NOW);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getPhoneVerifiedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("pwd-hash");
        verify(users, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("already verified: no save")
    void alreadyVerified() {
        User owner = owner(PHONE, null).withPhoneVerifiedAt(NOW.minus(Duration.ofDays(1)));
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(owner));
        service.verify(new VerifyOtpCommand(PHONE, "123456"));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("locked account still signs in by code (lockout not consulted)")
    void lockoutIgnored() {
        User owner = owner(PHONE, null).withLockoutState(5, NOW.plus(Duration.ofMinutes(10)));
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(owner));
        assertThat(service.verify(new VerifyOtpCommand(PHONE, "123456")).loginToken()).isEqualTo("login-token");
    }

    @Test
    @DisplayName("right code, unknown email: account created (OWNER, no password, email verified), memberships empty")
    void newEmailAccount() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(users.insertIfAbsent(any())).thenReturn(true);

        LoginSessionResult result = service.verify(new VerifyOtpCommand("Ana@Ex.com", "123456"));

        assertThat(result.memberships()).isEmpty();
        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insertIfAbsent(created.capture());
        User u = created.getValue();
        assertThat(u.getEmail()).isEqualTo(EMAIL);
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.hasPassword()).isFalse();
        assertThat(u.getRole()).isEqualTo(Role.OWNER);
        assertThat(u.getEmailVerifiedAt()).isEqualTo(NOW);
        assertThat(u.getPhoneVerifiedAt()).isNull();
        verify(jwt).generateLoginToken(u.getId());
    }

    @Test
    @DisplayName("unknown phone: account created with the phone verified")
    void newPhoneAccount() {
        when(users.insertIfAbsent(any())).thenReturn(true);
        service.verify(new VerifyOtpCommand(PHONE, "123456"));
        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insertIfAbsent(created.capture());
        assertThat(created.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(created.getValue().getPhoneVerifiedAt()).isEqualTo(NOW);
        assertThat(created.getValue().getEmail()).isNull();
    }

    @Test
    @DisplayName("concurrent registration: insert conflict → re-read once and use the existing account")
    void uniqueConflictReRead() {
        User other = owner(null, EMAIL);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty(), Optional.of(other));
        when(users.insertIfAbsent(any())).thenReturn(false);

        service.verify(new VerifyOtpCommand(EMAIL, "123456"));

        verify(users, times(2)).findByEmail(EMAIL);
        verify(jwt).generateLoginToken(other.getId());
    }

    @Test
    @DisplayName("insert conflict and still absent → USER_ALREADY_EXISTS")
    void uniqueConflictStillAbsent() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(users.insertIfAbsent(any())).thenReturn(false);
        assertCode(() -> service.verify(new VerifyOtpCommand(EMAIL, "123456")), ErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("wrong code ×4 → OTP_INVALID with attempts recorded; 5th → OTP_TOO_MANY_ATTEMPTS")
    void wrongCodes() {
        when(repo.recordFailure(CHALLENGE_ID, 5, NOW)).thenReturn(1, 2, 3, 4, 5);
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "000000")), ErrorCode.OTP_INVALID);
        }
        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "000000")), ErrorCode.OTP_TOO_MANY_ATTEMPTS);
        verify(repo, times(5)).recordFailure(CHALLENGE_ID, 5, NOW);
        verify(repo, never()).consume(any(), any());
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("malformed code counts as a wrong code (bcrypt not called)")
    void malformedCode() {
        when(repo.recordFailure(CHALLENGE_ID, 5, NOW)).thenReturn(1);
        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "12ab")), ErrorCode.OTP_INVALID);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("no live challenge (expired, used, never requested) → OTP_INVALID")
    void noChallenge() {
        when(repo.lockLatestActive(PHONE, NOW)).thenReturn(Optional.empty());
        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "123456")), ErrorCode.OTP_INVALID);
        verify(repo, never()).recordFailure(any(), anyInt(), any());
    }

    @Test
    @DisplayName("20 failures in 24 h → OTP_TOO_MANY_REQUESTS before any challenge read")
    void dailyCap() {
        when(repo.sumFailuresSince(PHONE, NOW.minus(Duration.ofHours(24)))).thenReturn(20);
        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "123456")), ErrorCode.OTP_TOO_MANY_REQUESTS);
        verify(repo, never()).lockLatestActive(anyString(), any());
    }

    @Test
    @DisplayName("bad identifier → VALIDATION_FAILED; other country → PHONE_COUNTRY_NOT_SUPPORTED")
    void validation() {
        assertCode(() -> service.verify(new VerifyOtpCommand("abc", "123456")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.verify(new VerifyOtpCommand("+33612345678", "123456")),
                ErrorCode.PHONE_COUNTRY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("review account: fixed code signs in without a challenge; a wrong code takes the normal path")
    void reviewAccount() {
        when(users.insertIfAbsent(any())).thenReturn(true);
        assertThat(service.verify(new VerifyOtpCommand("Review@BrefPoint.test", "246810")).loginToken())
                .isEqualTo("login-token");
        verifyNoInteractions(repo);

        when(repo.lockLatestActive("review@brefpoint.test", NOW)).thenReturn(Optional.empty());
        assertCode(() -> service.verify(new VerifyOtpCommand("review@brefpoint.test", "246811")),
                ErrorCode.OTP_INVALID);
    }

    @Test
    @DisplayName("wrong-code attempts are committed with the error (noRollbackFor DomainException)")
    void noRollback() throws NoSuchMethodException {
        Transactional tx = VerifyOtpService.class.getMethod("verify", VerifyOtpCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.noRollbackFor()).contains(DomainException.class);
    }

    @Test
    @DisplayName("31st check in 1 h from one IP → OTP_TOO_MANY_REQUESTS, review account included, before any SQL")
    void verifyPerIpLimit() {
        when(users.insertIfAbsent(any())).thenReturn(true);
        for (int i = 0; i < 30; i++) {
            service.verify(new VerifyOtpCommand("review@brefpoint.test", "246810", "41.202.9.9"));
        }
        clearInvocations(repo, users);
        assertCode(() -> service.verify(new VerifyOtpCommand("review@brefpoint.test", "246810", "41.202.9.9")),
                ErrorCode.OTP_TOO_MANY_REQUESTS);
        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "123456", "41.202.9.9")),
                ErrorCode.OTP_TOO_MANY_REQUESTS);
        verifyNoInteractions(repo, users);
    }

    @Test
    @DisplayName("SUPER_ADMIN never signs in by code: OTP_INVALID, no verified date, no account created")
    void superAdminRefused() {
        User admin = new User(UUID.randomUUID(), PHONE, "pwd-hash", Role.SUPER_ADMIN, true, NOW, 0, null,
                null, null, null, null, null, null);
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(admin));

        assertCode(() -> service.verify(new VerifyOtpCommand(PHONE, "123456")), ErrorCode.OTP_INVALID);
        verify(users, never()).save(any());
        verify(users, never()).insertIfAbsent(any());
        verify(jwt, never()).generateLoginToken(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN refused on the review path and after an insert conflict too")
    void superAdminRefusedReviewAndConflict() {
        User admin = new User(UUID.randomUUID(), null, "pwd-hash", Role.SUPER_ADMIN, true, NOW, 0, null,
                "review@brefpoint.test", null, null, null, null, null);
        when(users.findByEmail("review@brefpoint.test")).thenReturn(Optional.of(admin));
        assertCode(() -> service.verify(new VerifyOtpCommand("review@brefpoint.test", "246810")),
                ErrorCode.OTP_INVALID);

        User admin2 = new User(UUID.randomUUID(), null, "pwd-hash", Role.SUPER_ADMIN, true, NOW, 0, null,
                EMAIL, null, null, null, null, null);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty(), Optional.of(admin2));
        when(users.insertIfAbsent(any())).thenReturn(false);
        assertCode(() -> service.verify(new VerifyOtpCommand(EMAIL, "123456")), ErrorCode.OTP_INVALID);
        verify(users, never()).save(any());
        verify(jwt, never()).generateLoginToken(any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}

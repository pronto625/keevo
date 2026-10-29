package com.keevo.identity.auth.application.service;

import com.keevo.shared.event.catalog.auth.PasswordResetEvent;
import com.keevo.identity.auth.domain.model.PasswordResetToken;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.ResetPasswordCommand;
import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResetPasswordService")
class ResetPasswordServiceTest {

    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks ResetPasswordService service;

    UUID userId;
    UUID employeeId;
    String phoneNumber;
    String validCode;
    String validCodeHash;
    String newPassword;
    PasswordResetToken token;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        phoneNumber = "+237600000000";
        validCode = "123456";
        validCodeHash = "$2a$12$hashedCode";
        newPassword = "newPass1";
        token = PasswordResetToken.create(userId, phoneNumber, validCodeHash,
                Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("should reset password when code is valid")
    void shouldResetPasswordWhenCodeValid() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        stubClaimAndConsume();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, phoneNumber, "$2a$12$oldPassword", Role.OWNER, true, Instant.now())));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$12$newHash");
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$2a$12$newHash");
        verify(passwordResetTokenRepository).tryClaimAttempt(token.id(), 5);
        verify(passwordResetTokenRepository).markConsumed(eq(token.id()), any(Instant.class));
        verify(tokenRevocationPort).revokeAllSessionsEverywhere(userId);
        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(eventPublisher).publishEvent(any(PasswordResetEvent.class));
    }

    @Test
    @DisplayName("should reject expired code")
    void shouldRejectExpiredCode() {
        PasswordResetToken expired = PasswordResetToken.create(userId, phoneNumber, validCodeHash,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(expired));

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword)));
        assertThat(thrown).isInstanceOf(DomainException.class);
        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_CODE.name());
        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("should reject already consumed code")
    void shouldRejectAlreadyConsumedCode() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword)));
        assertThat(thrown).isInstanceOf(DomainException.class);
        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_CODE.name());
    }

    @Test
    @DisplayName("should lock token after 5 failed attempts")
    void shouldLockTokenAfterFiveFailedAttempts() {
        PasswordResetToken locked = token.withAttempts(5);
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(locked));

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword)));
        assertThat(thrown).isInstanceOf(DomainException.class);
        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.CODE_LOCKED.name());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("should revoke all sessions across all memberships")
    void shouldRevokeAllSessionsAcrossAllMemberships() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        stubClaimAndConsume();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, phoneNumber, "$2a$12$oldPassword", Role.OWNER, true, Instant.now())));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$12$newHash");
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword));
        verify(tokenRevocationPort).revokeAllSessionsEverywhere(userId);
    }

    @Test
    @DisplayName("should reject weak password — validated BEFORE the attempt claim (12.12: no attempt burnt)")
    void shouldRejectWeakPassword() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));

        Throwable t1 = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, "short")));
        assertThat(t1).isInstanceOf(DomainException.class);
        assertThat(((DomainException) t1).getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());

        Throwable t2 = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, "abcdefgh")));
        assertThat(t2).isInstanceOf(DomainException.class);
        assertThat(((DomainException) t2).getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());

        verify(passwordResetTokenRepository, never()).tryClaimAttempt(any(), anyInt());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("should emit PasswordResetEvent on success")
    void shouldEmitPasswordResetEvent() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        stubClaimAndConsume();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, phoneNumber, "$2a$12$oldPassword", Role.OWNER, true, Instant.now())));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$12$newHash");
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword));

        ArgumentCaptor<PasswordResetEvent> eventCaptor = ArgumentCaptor.forClass(PasswordResetEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("should set passwordChangeRequired=false when employee")
    void shouldSetPasswordChangeRequiredFalseWhenEmployee() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        stubClaimAndConsume();
        User empUser = new User(userId, phoneNumber, "$2a$12$oldPassword", Role.EMPLOYEE, true, Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(empUser));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$12$newHash");
        Employee employee = new Employee(employeeId, userId, UUID.randomUUID(), "Test", "User",
                EmployeeStatus.ACTIVE, true, Instant.now());
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));

        service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword));
        verify(employeeRepository).updatePasswordChangeRequired(employeeId, false);
    }

    @Test
    @DisplayName("should not issue new JWT/refresh tokens")
    void shouldNotIssueNewTokens() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        stubClaimAndConsume();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, phoneNumber, "$2a$12$oldPassword", Role.OWNER, true, Instant.now())));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$12$newHash");
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword));
        // No JWT generation — service has no JwtTokenProvider dependency
    }
    // Each wrong code throws DomainException. With the default rollback rule
    // the attempts increment is rolled back too, the lock at 5 attempts is
    // never reached, and a 6-digit code can be brute-forced. Mocks cannot see
    // a rollback, so this pins the transaction rule itself.
    @Test
    @DisplayName("wrong-code attempts survive the DomainException (no rollback)")
    void execute_keepsAttemptCounterWhenCodeIsWrong() throws NoSuchMethodException {
        var tx = ResetPasswordService.class
                .getMethod("execute", ResetPasswordCommand.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(DomainException.class);
    }

    // ── Story 12.12 — atomic attempts, CAS consumption, 72-byte cap ─────────

    private void stubClaimAndConsume() {
        lenient().when(passwordResetTokenRepository.tryClaimAttempt(any(UUID.class), eq(5))).thenReturn(true);
        lenient().when(passwordResetTokenRepository.markConsumed(any(UUID.class), any(Instant.class))).thenReturn(true);
    }

    @Test
    @DisplayName("12.12 — wrong code: attempt claimed atomically BEFORE bcrypt, no read-modify-write save")
    void wrongCodeClaimsAttemptBeforeCompare() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.of(token));
        when(passwordResetTokenRepository.tryClaimAttempt(token.id(), 5)).thenReturn(true);
        when(passwordEncoder.matches("000000", validCodeHash)).thenReturn(false);

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, "000000", newPassword)));

        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_CODE.name());
        var order = inOrder(passwordResetTokenRepository, passwordEncoder);
        order.verify(passwordResetTokenRepository).tryClaimAttempt(token.id(), 5);
        order.verify(passwordEncoder).matches("000000", validCodeHash);
        verify(passwordResetTokenRepository, never()).save(any());
        verify(passwordResetTokenRepository, never()).markConsumed(any(), any());
    }

    @Test
    @DisplayName("12.12 — concurrent guess loses the claim (cap reached) → CODE_LOCKED, bcrypt never run")
    void claimRefusedMeansLocked() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.of(token));
        when(passwordResetTokenRepository.tryClaimAttempt(token.id(), 5)).thenReturn(false);

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword)));

        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.CODE_LOCKED.name());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("12.12 — double reset: CAS consume lost → INVALID_OR_EXPIRED_CODE, password untouched")
    void lostConsumeRaceRejects() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.of(token));
        when(passwordResetTokenRepository.tryClaimAttempt(token.id(), 5)).thenReturn(true);
        when(passwordEncoder.matches(validCode, validCodeHash)).thenReturn(true);
        when(passwordResetTokenRepository.markConsumed(eq(token.id()), any(Instant.class))).thenReturn(false);

        Throwable thrown = catchThrowable(() ->
                service.execute(new ResetPasswordCommand(phoneNumber, validCode, newPassword)));

        assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_CODE.name());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        verify(tokenRevocationPort, never()).revokeAllSessionsEverywhere(any());
    }

    @Test
    @DisplayName("12.12 — password over 72 UTF-8 bytes → VALIDATION_FAILED (never reaches bcrypt)")
    void rejectsPasswordOver72Bytes() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.of(token));

        String ascii73 = "a1" + "x".repeat(71);
        String multiByte = "é1" + "é".repeat(36); // 38 chars, 75 bytes
        for (String pwd : new String[]{ascii73, multiByte}) {
            Throwable thrown = catchThrowable(() ->
                    service.execute(new ResetPasswordCommand(phoneNumber, validCode, pwd)));
            assertThat(((DomainException) thrown).getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        }
        verify(passwordEncoder, never()).encode(anyString());
        verify(passwordResetTokenRepository, never()).tryClaimAttempt(any(), anyInt());
        verify(passwordResetTokenRepository, never()).markConsumed(any(), any());
    }

    @Test
    @DisplayName("12.12 — phone without '+' is normalised before the token lookup")
    void normalisesPhone() {
        when(passwordResetTokenRepository.findActiveByPhoneNumber(phoneNumber)).thenReturn(Optional.empty());

        catchThrowable(() -> service.execute(new ResetPasswordCommand("237600000000", validCode, newPassword)));

        verify(passwordResetTokenRepository).findActiveByPhoneNumber(phoneNumber);
    }
}

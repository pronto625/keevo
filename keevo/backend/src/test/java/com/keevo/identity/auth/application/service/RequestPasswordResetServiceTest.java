package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PasswordResetRequestedEvent;
import com.keevo.identity.auth.domain.model.PasswordResetToken;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetCommand;
import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RequestPasswordResetServiceTest — TDD tests for Story 14.12 forgot-password flow.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequestPasswordResetService")
class RequestPasswordResetServiceTest {

    @Mock PasswordResetRateLimiter rateLimiter;
    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock WhatsAppPort whatsAppPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks RequestPasswordResetService service;

    UUID userId;
    String phoneNumber;
    User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        phoneNumber = "+237600000000";
        user = new User(userId, phoneNumber, "$2a$12$hashed",
                Role.OWNER, true, Instant.now());
    }

    @Test
    @DisplayName("should generate and send OTP when user exists and rate-limit OK")
    void shouldGenerateAndSendOtpWhenUserExists() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(true);
        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$bcryptHash");

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        // Verify OTP was generated and hashed
        verify(passwordEncoder).encode(anyString());
        // Verify token was saved
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        // Verify previous tokens were invalidated
        verify(passwordResetTokenRepository).invalidateActiveTokensForUser(userId);
        // Verify WhatsApp was called
        verify(whatsAppPort).sendOtp(eq(phoneNumber), anyString());
        // Verify event was published
        ArgumentCaptor<PasswordResetRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(eventCaptor.getValue().phoneNumber()).isEqualTo(phoneNumber);
    }

    @Test
    @DisplayName("should return silently when user does not exist (anti-enumeration)")
    void shouldReturnSilentlyWhenUserDoesNotExist() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(true);
        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.empty());

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        // No token saved, no WhatsApp, no event
        verify(passwordResetTokenRepository, never()).save(any());
        verify(whatsAppPort, never()).sendOtp(anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("should invalidate previous unconsumed tokens before creating new one")
    void shouldInvalidatePreviousUnconsumedTokensForUser() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(true);
        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$bcryptHash");

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        // invalidateActiveTokensForUser must be called BEFORE save
        var inOrder = inOrder(passwordResetTokenRepository);
        inOrder.verify(passwordResetTokenRepository).invalidateActiveTokensForUser(userId);
        inOrder.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    @DisplayName("should rate-limit: block second request within 1 minute per phone")
    void shouldRateLimitOnePerMinutePerPhone() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(false);

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        // No lookups, no token, no WhatsApp, no event
        verify(userRepository, never()).findByPhoneNumber(anyString());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(whatsAppPort, never()).sendOtp(anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("should rate-limit: block 6th request within 1 hour per phone")
    void shouldRateLimitFivePerHourPerPhone() {
        // rateLimiter already handles 5/hour cap — simulate it returning false
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(false);

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        verify(userRepository, never()).findByPhoneNumber(anyString());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(whatsAppPort, never()).sendOtp(anyString(), anyString());
    }

    @Test
    @DisplayName("should hash OTP with bcrypt, never store plaintext")
    void shouldHashOtpNotStorePlaintext() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(true);
        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$bcryptHash");

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        ArgumentCaptor<PasswordResetToken> tokenCaptor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());

        PasswordResetToken saved = tokenCaptor.getValue();
        // The hash must be bcrypt-prefixed (from PasswordEncoder)
        assertThat(saved.codeHash()).startsWith("$2a$12$");
        // The hash must differ from any 6-digit number (no plaintext leak)
        assertThat(saved.codeHash()).doesNotContain("000000");
    }

    @Test
    @DisplayName("should not publish event when user does not exist")
    void shouldNotPublishEventWhenUserDoesNotExist() {
        when(rateLimiter.tryAcquire(phoneNumber)).thenReturn(true);
        when(userRepository.findByPhoneNumber(phoneNumber)).thenReturn(Optional.empty());

        service.execute(new RequestPasswordResetCommand(phoneNumber));

        verify(eventPublisher, never()).publishEvent(any(PasswordResetRequestedEvent.class));
    }
}

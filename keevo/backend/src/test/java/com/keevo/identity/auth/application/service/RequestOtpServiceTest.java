package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.OtpChannel;
import com.keevo.identity.auth.domain.port.in.RequestOtpCommand;
import com.keevo.identity.auth.domain.port.out.OtpChallengeRepository;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RequestOtpService (Story 19.2)")
class RequestOtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final String PHONE = "+237699001122";

    @Mock OtpChallengeRepository repo;
    @Mock PasswordEncoder encoder;
    @Mock SmsSender sms;
    @Mock EmailSender email;
    @Mock WhatsAppSender whatsApp;
    @Mock PlatformTransactionManager txManager;
    @Mock TransactionStatus txStatus;

    MutableTestClock clock = new MutableTestClock(NOW);
    RequestOtpService service;

    @BeforeEach
    void setUp() {
        service = service(new OtpSettings("237,241,235,243,221", "review@brefpoint.test", "246810"));
        when(encoder.encode(anyString())).thenReturn("$2a$12$hash");
        when(sms.send(any())).thenReturn(DeliveryResult.deliveredBy("mboasms"));
        when(email.send(any())).thenReturn(DeliveryResult.deliveredBy("resend"));
        when(whatsApp.canSendCodes()).thenReturn(true);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
    }

    private RequestOtpService service(OtpSettings settings) {
        return new RequestOtpService(settings, new OtpRateLimiter(clock, 3, 5, 10, 30), repo, encoder,
                sms, email, whatsApp, clock, new TransactionTemplate(txManager));
    }

    private static RequestOtpCommand cmd(String identifier, OtpChannel channel) {
        return new RequestOtpCommand(identifier, channel, "10.0.0.1", null);
    }

    @Test
    @DisplayName("phone, no channel: hash before any SQL, previous codes consumed, SMS + WhatsApp after commit only")
    void phoneDefaultChannels() {
        int expiresIn = service.request(cmd("+237 6 99 00 11 22", null));

        InOrder order = inOrder(encoder, txManager, repo, sms, whatsApp);
        order.verify(encoder).encode(anyString());
        order.verify(txManager).getTransaction(any());
        order.verify(repo).insert(any(), anyString(), anyString(), any(), any());
        order.verify(txManager).commit(txStatus);
        order.verify(sms).send(any());
        order.verify(whatsApp).sendCode(anyString(), anyString(), anyString(), anyString());
        assertThat(expiresIn).isEqualTo(300);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(code.capture());
        assertThat(code.getValue()).matches("[1-9]\\d{5}");
        verify(repo).invalidateActive(PHONE, NOW);
        verify(repo).insert(any(), eq(PHONE), eq("$2a$12$hash"), eq(NOW.plus(Duration.ofMinutes(5))), eq(NOW));
        ArgumentCaptor<SmsMessage> smsMsg = ArgumentCaptor.forClass(SmsMessage.class);
        verify(sms).send(smsMsg.capture());
        assertThat(smsMsg.getValue().to()).isEqualTo(PHONE);
        assertThat(smsMsg.getValue().text()).isEqualTo(
                "Votre code de connexion Bref Point : " + code.getValue() + ". Valable 5 minutes. Ne le partagez pas.");
        verify(whatsApp).sendCode(PHONE, code.getValue(), smsMsg.getValue().text(), "fr");
        verifyNoInteractions(email);
    }

    @Test
    @DisplayName("unknown email, EMAIL, English: email to the lowercased address with HTML body")
    void emailEnglish() {
        service.request(new RequestOtpCommand("Ana@Ex.com", OtpChannel.EMAIL, null, "en-US"));

        ArgumentCaptor<EmailMessage> msg = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(msg.capture());
        assertThat(msg.getValue().to()).isEqualTo("ana@ex.com");
        assertThat(msg.getValue().subject()).isEqualTo("Your Bref Point verification code");
        assertThat(msg.getValue().html()).isNotBlank();
        verifyNoInteractions(sms, whatsApp);
    }

    @Test
    @DisplayName("Story 19.9b — English request: WhatsApp code in en with the English fallback text; gate is "
            + "canSendCodes, not isConfigured (meta with only Meta keys)")
    void whatsAppEnglish() {
        lenient().when(whatsApp.isConfigured()).thenReturn(false);
        service.request(new RequestOtpCommand(PHONE, OtpChannel.WHATSAPP, "10.0.0.1", "en-US"));
        verify(whatsApp).sendCode(eq(PHONE), matches("[1-9]\\d{5}"), startsWith("Your Bref Point login code: "),
                eq("en"));
    }

    @Test
    @DisplayName("single phone channel: only that channel")
    void singleChannel() {
        service.request(cmd(PHONE, OtpChannel.WHATSAPP));
        verify(whatsApp).sendCode(eq(PHONE), matches("[1-9]\\d{5}"), anyString(), eq("fr"));
        verifyNoInteractions(sms, email);
    }

    @Test
    @DisplayName("channel mismatch and bad identifier → VALIDATION_FAILED; other country → PHONE_COUNTRY_NOT_SUPPORTED")
    void validation() {
        assertCode(() -> service.request(cmd("ana@ex.com", OtpChannel.SMS)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.request(cmd(PHONE, OtpChannel.EMAIL)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.request(cmd("abc", null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.request(cmd("+33612345678", null)), ErrorCode.PHONE_COUNTRY_NOT_SUPPORTED);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("provider failure (exception or not delivered) never changes the answer")
    void providerFailureSwallowed() {
        when(sms.send(any())).thenThrow(new IllegalStateException("To +237699001122 invalid"));
        doThrow(new RuntimeException("down")).when(whatsApp).sendCode(anyString(), anyString(), anyString(), anyString());
        assertThat(service.request(cmd(PHONE, null))).isEqualTo(300);

        when(email.send(any())).thenReturn(DeliveryResult.notDelivered());
        assertThat(service.request(cmd("ana@ex.com", null))).isEqualTo(300);
    }

    @Test
    @DisplayName("WhatsApp cannot send codes: nothing handed to the no-op stub")
    void whatsAppNotConfigured() {
        when(whatsApp.canSendCodes()).thenReturn(false);
        service.request(cmd(PHONE, null));
        verify(whatsApp, never()).sendCode(anyString(), anyString(), anyString(), anyString());
        verify(whatsApp, never()).sendReport(anyString(), anyString());
        verify(sms).send(any());
    }

    @Test
    @DisplayName("4th request in 10 min → OTP_TOO_MANY_REQUESTS")
    void rateLimited() {
        for (int i = 0; i < 3; i++) service.request(cmd(PHONE, OtpChannel.SMS));
        assertCode(() -> service.request(cmd(PHONE, OtpChannel.SMS)), ErrorCode.OTP_TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("20 failures in 24 h → OTP_TOO_MANY_REQUESTS, nothing stored or sent")
    void dailyCap() {
        when(repo.sumFailuresSince(PHONE, NOW.minus(Duration.ofHours(24)))).thenReturn(20);
        assertCode(() -> service.request(cmd(PHONE, null)), ErrorCode.OTP_TOO_MANY_REQUESTS);
        verify(repo, never()).insert(any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(sms, whatsApp);
    }

    @Test
    @DisplayName("review account: no limit, nothing stored, nothing sent")
    void reviewAccount() {
        for (int i = 0; i < 12; i++) {
            assertThat(service.request(cmd("Review@BrefPoint.test", null))).isEqualTo(300);
        }
        verifyNoInteractions(repo, encoder, sms, email, whatsApp);
    }

    @Test
    @DisplayName("platform send only: no tenant messaging dependency (never counted on a tenant quota)")
    void noTenantMessaging() {
        Constructor<?>[] ctors = RequestOtpService.class.getConstructors();
        assertThat(ctors).hasSize(1);
        assertThat(Arrays.stream(ctors[0].getParameterTypes()).map(Class::getSimpleName))
                .doesNotContain("TenantMessaging", "UsageQuotaService");
    }

    @Test
    @DisplayName("generated codes are 6 digits in 100000–999999")
    void codeRange() {
        for (int i = 0; i < 1000; i++) {
            int code = Integer.parseInt(service.generateCode());
            assertThat(code).isBetween(100_000, 999_999);
        }
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("database part fails → nothing sent")
    void dbFailureSendsNothing() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("down"))
                .when(repo).insert(any(), anyString(), anyString(), any(), any());
        assertThatThrownBy(() -> service.request(cmd(PHONE, null)))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(txManager, never()).commit(any());
        verifyNoInteractions(sms, whatsApp, email);
    }
}

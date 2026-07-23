package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FailoverWhatsAppAdapterTest — characterizes failover behavior.
 * Story 15.3 — Task 2.2.
 */
@ExtendWith(MockitoExtension.class)
class FailoverWhatsAppAdapterTest {

    @Mock
    private RestTemplate wassenderRestTemplate;
    @Mock
    private RestTemplate twilioRestTemplate;

    private WassenderProperties wassenderProperties;
    private TwilioProperties twilioProperties;
    private FailoverWhatsAppAdapter adapter;

    @BeforeEach
    void setUp() {
        wassenderProperties = new WassenderProperties("https://www.wasenderapi.com", "was-token-1234");
        twilioProperties = new TwilioProperties("ACtest", "twilio-auth", "+14155238886");
        adapter = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                wassenderProperties, twilioProperties);
    }

    // ── Wassender succeeds, Twilio never called ────────────────────────

    @Test
    void sendReport_whenWassenderSucceeds_twilioNeverCalled() {
        when(wassenderRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Report"));

        verify(twilioRestTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    // ── Wassender fails → Twilio fallback succeeds ─────────────────────

    @Test
    void sendReport_whenWassenderFails_fallsBackToTwilio() {
        when(wassenderRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Wassender down"));
        when(twilioRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Report"));

        verify(twilioRestTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    // ── Both fail → WhatsAppDeliveryException ──────────────────────────

    @Test
    void sendReport_whenBothFail_throwsWhatsAppDeliveryException_withBothProviderNames() {
        when(wassenderRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Wassender down"));
        when(twilioRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Twilio down"));

        WhatsAppDeliveryException ex = assertThrows(WhatsAppDeliveryException.class,
                () -> adapter.sendReport("+237699000080", "Report"));
        assertTrue(ex.getMessage().contains("Wassender"), "Message should mention Wassender");
        assertTrue(ex.getMessage().contains("Twilio"), "Message should mention Twilio");
    }

    // ── Wassender API token blank → skipped, Twilio tried directly ─────

    @Test
    void sendReport_whenWassenderTokenBlank_skipsWassender_triesTwilioDirectly() {
        // Token is blank (not configured)
        WassenderProperties blankToken = new WassenderProperties("https://api.example.com", "   ");
        adapter = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                blankToken, twilioProperties);
        when(twilioRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Report"));

        verify(wassenderRestTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
        verify(twilioRestTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendReport_whenWassenderTokenNull_skipsWassender_triesTwilioDirectly() {
        WassenderProperties nullToken = new WassenderProperties("https://api.example.com", null);
        adapter = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                nullToken, twilioProperties);
        when(twilioRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Report"));

        verify(wassenderRestTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    // ── isConfigured matrix ───────────────────────────────────────────

    @Test
    void isConfigured_returnsTrue_whenBothProvidersConfigured() {
        assertTrue(adapter.isConfigured());
    }

    @Test
    void isConfigured_returnsTrue_whenOnlyWassenderConfigured() {
        TwilioProperties emptyTwilio = new TwilioProperties("", "", "");
        FailoverWhatsAppAdapter a = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                wassenderProperties, emptyTwilio);
        assertTrue(a.isConfigured());
    }

    @Test
    void isConfigured_returnsTrue_whenOnlyTwilioConfigured() {
        WassenderProperties emptyWas = new WassenderProperties("https://api.example.com", "   ");
        FailoverWhatsAppAdapter a = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                emptyWas, twilioProperties);
        assertTrue(a.isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenNeitherProviderConfigured() {
        WassenderProperties emptyWas = new WassenderProperties("https://api.example.com", "");
        TwilioProperties emptyTwilio = new TwilioProperties("", "", "");
        FailoverWhatsAppAdapter a = new FailoverWhatsAppAdapter(wassenderRestTemplate, twilioRestTemplate,
                emptyWas, emptyTwilio);
        assertFalse(a.isConfigured());
    }
}

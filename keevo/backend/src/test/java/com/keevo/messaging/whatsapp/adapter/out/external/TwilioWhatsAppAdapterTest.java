package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TwilioWhatsAppAdapterTest — characterizes Twilio adapter behavior.
 * Story 15.3 — Task 2.1.
 */
@ExtendWith(MockitoExtension.class)
class TwilioWhatsAppAdapterTest {

    @Mock
    private RestTemplate restTemplate;

    private TwilioProperties properties;
    private TwilioWhatsAppAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new TwilioProperties("ACtest123", "authtoken1234", "+14155238886");
        adapter = new TwilioWhatsAppAdapter(restTemplate, properties);
    }

    @Test
    void sendReport_success_returnsNormally() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Daily report"));
    }

    @Test
    void sendReport_setsCorrectHeaders_basicAuthAndFormBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendReport("+237699000080", "Rapport test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertTrue(headers.containsKey("Authorization"), "Basic Auth header missing");
        assertTrue(headers.getFirst("Authorization").startsWith("Basic "), "Expected Basic auth");

        MultiValueMap<String, String> body = captor.getValue().getBody();
        assertNotNull(body);
        assertEquals("whatsapp:+14155238886", body.getFirst("From"));
        assertEquals("whatsapp:+237699000080", body.getFirst("To"));
    }

    @Test
    void sendReport_4xxError_throwsWhatsAppDeliveryException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThrows(WhatsAppDeliveryException.class,
                () -> adapter.sendReport("+237699000080", "Report"));
    }

    @Test
    void sendReport_5xxError_throwsWhatsAppDeliveryException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(WhatsAppDeliveryException.class,
                () -> adapter.sendReport("+237699000080", "Report"));
    }

    @Test
    void sendReport_timeout_throwsWhatsAppDeliveryException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Timeout", new SocketTimeoutException("Read timed out")));

        assertThrows(WhatsAppDeliveryException.class,
                () -> adapter.sendReport("+237699000080", "Report"));
    }

    @Test
    void sendReport_sanitizesPhoneNumber() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendReport("+237 699-00-00-80", "Report");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        MultiValueMap<String, String> body = captor.getValue().getBody();
        assertNotNull(body);
        assertEquals("whatsapp:+237699000080", body.getFirst("To"),
                "Phone should be sanitized to E.164 without spaces/dashes/parens");
    }

    @Test
    void isConfigured_returnsTrue_whenAllFieldsSet() {
        assertTrue(adapter.isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenAccountSidBlank() {
        TwilioProperties emptySid = new TwilioProperties("  ", "auth", "+123");
        TwilioWhatsAppAdapter a = new TwilioWhatsAppAdapter(restTemplate, emptySid);
        assertFalse(a.isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenAuthTokenBlank() {
        TwilioProperties emptyToken = new TwilioProperties("ACx", "", "+123");
        TwilioWhatsAppAdapter a = new TwilioWhatsAppAdapter(restTemplate, emptyToken);
        assertFalse(a.isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenFromNumberBlank() {
        TwilioProperties emptyFrom = new TwilioProperties("ACx", "auth", null);
        TwilioWhatsAppAdapter a = new TwilioWhatsAppAdapter(restTemplate, emptyFrom);
        assertFalse(a.isConfigured());
    }
}

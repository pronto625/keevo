package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
import com.keevo.messaging.channel.domain.model.SendOutcome;
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
    void sendText_success_returnsNormally() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertEquals(SendOutcome.DELIVERED, adapter.sendText("+237699000080", "Daily report"));
    }

    @Test
    void sendText_setsCorrectHeaders_basicAuthAndFormBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendText("+237699000080", "Rapport test");

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
    void sendText_4xxError_returnsFailed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertEquals(SendOutcome.FAILED, adapter.sendText("+237699000080", "Report"));
    }

    @Test
    void sendText_5xxError_returnsFailed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(SendOutcome.FAILED, adapter.sendText("+237699000080", "Report"));
    }

    @Test
    void sendText_timeout_returnsFailed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Timeout", new SocketTimeoutException("Read timed out")));

        assertEquals(SendOutcome.FAILED, adapter.sendText("+237699000080", "Report"));
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

    @Test
    void sendCodeText_readTimeout_isUnknown_soNoSecondCode() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Timeout", new SocketTimeoutException("Read timed out")));

        assertEquals(SendOutcome.UNKNOWN, adapter.sendCodeText("+237699000080", "code"));
        assertEquals(SendOutcome.FAILED, adapter.sendText("+237699000080", "report"));
    }

    @Test
    void sendCodeText_connectFailureOrHttpError_isFailed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("I/O", new java.net.ConnectException("refused")))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(SendOutcome.FAILED, adapter.sendCodeText("+237699000080", "code"));
        assertEquals(SendOutcome.FAILED, adapter.sendCodeText("+237699000080", "code"));
    }
}

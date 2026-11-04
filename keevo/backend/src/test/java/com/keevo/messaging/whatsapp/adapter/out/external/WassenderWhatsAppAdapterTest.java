package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WassenderWhatsAppAdapterTest {

    @Mock
    private RestTemplate restTemplate;

    private WassenderProperties properties;
    private WassenderWhatsAppAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new WassenderProperties(
                "https://www.wasenderapi.com",
                "test-api-token-1234567890");
        adapter = new WassenderWhatsAppAdapter(restTemplate, properties);
    }

    @Test
    void sendText_success_returnsNormally() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"id\":\"msg-1\",\"status\":\"queued\"}", HttpStatus.OK));

        assertEquals(SendOutcome.DELIVERED, adapter.sendText("+237699000080", "Daily report content"));
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
    void sendText_putsPhoneAndEscapedTextInBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendText("+237699000080", "Ligne \"1\"\nLigne 2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        String body = captor.getValue().getBody();
        assertNotNull(body);
        assertEquals("{\"to\":\"+237699000080\",\"text\":\"Ligne \\\"1\\\"\\nLigne 2\"}", body);
    }

    @Test
    void name_isWassender() {
        assertEquals("wassender", adapter.name());
        assertFalse(adapter.supportsTemplates());
    }

    @Test
    void isConfigured_returnsTrue() {
        assertTrue(adapter.isConfigured());
    }

    @Test
    void isConfigured_missingOrBlankToken_returnsFalse() {
        assertFalse(new WassenderWhatsAppAdapter(restTemplate,
                new WassenderProperties("https://www.wasenderapi.com", null)).isConfigured());
        assertFalse(new WassenderWhatsAppAdapter(restTemplate,
                new WassenderProperties("https://www.wasenderapi.com", "   ")).isConfigured());
    }

    @Test
    void sendText_setsCorrectHeaders() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendText("+237699000080", "Report");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://www.wasenderapi.com/api/send-message"), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertEquals("Bearer test-api-token-1234567890", headers.getFirst("Authorization"));
        assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
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

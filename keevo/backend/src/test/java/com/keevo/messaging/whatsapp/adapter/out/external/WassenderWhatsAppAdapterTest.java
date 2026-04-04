package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
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
    void sendReport_success_returnsNormally() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"id\":\"msg-1\",\"status\":\"queued\"}", HttpStatus.OK));

        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Daily report content"));
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
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        String body = captor.getValue().getBody();
        assertNotNull(body);
        assertTrue(body.contains("+237699000080"), "Phone should be sanitized: " + body);
        assertFalse(body.contains(" "), "Spaces should be removed from phone in body");
    }

    @Test
    void isConfigured_returnsTrue() {
        assertTrue(adapter.isConfigured());
    }

    @Test
    void sendReport_setsCorrectHeaders() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        adapter.sendReport("+237699000080", "Report");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://www.wasenderapi.com/api/send-message"), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertEquals("Bearer test-api-token-1234567890", headers.getFirst("Authorization"));
        assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
    }
}

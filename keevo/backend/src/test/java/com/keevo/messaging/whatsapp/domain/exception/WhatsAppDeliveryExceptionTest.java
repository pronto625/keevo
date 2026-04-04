package com.keevo.messaging.whatsapp.domain.exception;

import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppDeliveryExceptionTest {

    @Test
    void exception_hasDomainCode_WHATSAPP_DELIVERY_FAILED() {
        WhatsAppDeliveryException ex = new WhatsAppDeliveryException("Test failure");
        assertEquals(ErrorCode.WHATSAPP_DELIVERY_FAILED, ex.getErrorCode());
        assertEquals("WHATSAPP_DELIVERY_FAILED", ex.getDomainCode());
    }

    @Test
    void exception_containsMessage() {
        WhatsAppDeliveryException ex = new WhatsAppDeliveryException("Wassender returned 500");
        assertTrue(ex.getMessage().contains("Wassender returned 500"));
    }

    @Test
    void exception_withCause() {
        RuntimeException cause = new RuntimeException("timeout");
        WhatsAppDeliveryException ex = new WhatsAppDeliveryException("Connection timeout", cause);
        assertEquals(cause, ex.getCause());
        assertEquals(ErrorCode.WHATSAPP_DELIVERY_FAILED, ex.getErrorCode());
    }
}

package com.keevo.messaging.whatsapp.adapter.out.noop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NoOpWhatsAppAdapterTest — characterizes the no-op stub adapter.
 * Story 15.3 — Task 2.3.
 */
class NoOpWhatsAppAdapterTest {

    private NoOpWhatsAppAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NoOpWhatsAppAdapter();
    }

    @Test
    void isConfigured_alwaysReturnsFalse() {
        assertFalse(adapter.isConfigured());
    }

    @Test
    void sendReport_neverThrows() {
        assertDoesNotThrow(() -> adapter.sendReport("+237699000080", "Test message"));
        assertDoesNotThrow(() -> adapter.sendReport("+1234567890", ""));
        assertDoesNotThrow(() -> adapter.sendReport("+000", null));
    }

    @Test
    void sendOtp_neverThrows_delegatesToSendReport() {
        // sendOtp is a default method that delegates to sendReport → never throws
        assertDoesNotThrow(() -> adapter.sendOtp("+237699000080", "123456"));
    }
}

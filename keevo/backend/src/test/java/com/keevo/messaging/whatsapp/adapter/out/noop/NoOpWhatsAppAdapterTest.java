package com.keevo.messaging.whatsapp.adapter.out.noop;

import com.keevo.messaging.channel.domain.model.SendOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NoOpWhatsAppAdapterTest — the logging stub provider. Story 15.3 — Task 2.3; Story 19.9b — never logs the text.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoOpWhatsAppAdapterTest {

    private NoOpWhatsAppAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NoOpWhatsAppAdapter();
    }

    @Test
    void nameIsNoop_andNeedsNoKey() {
        assertEquals("noop", adapter.name());
        assertTrue(adapter.isConfigured());
        assertFalse(adapter.supportsTemplates());
    }

    @Test
    void sendText_neverThrows_andReportsDelivered() {
        assertEquals(SendOutcome.DELIVERED, adapter.sendText("+237699000080", "Test message"));
        assertEquals(SendOutcome.DELIVERED, adapter.sendText("+1234567890", ""));
        assertEquals(SendOutcome.DELIVERED, adapter.sendText("+000", null));
    }

    @Test
    void sendText_logsNeitherTheTextNorTheFullPhone(CapturedOutput output) {
        adapter.sendText("+237699000080", "Votre code : 482913");
        assertFalse(output.getAll().contains("482913"));
        assertFalse(output.getAll().contains("699000080"));
        assertTrue(output.getAll().contains("***080"));
    }
}

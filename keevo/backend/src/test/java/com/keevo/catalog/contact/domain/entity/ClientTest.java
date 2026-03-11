package com.keevo.catalog.contact.domain.entity;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for the Client domain entity (Story 2.5).
 */
@DisplayName("Client domain entity")
class ClientTest {

    private static final UUID ID    = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid client with all fields constructs successfully")
    void should_construct_with_all_fields() {
        Client client = new Client(ID, "Alice Dupont", "+22670000001",
                "alice@example.com", "VIP client", false, NOW, NOW);

        assertEquals(ID,               client.id());
        assertEquals("Alice Dupont",   client.name());
        assertEquals("+22670000001",   client.phone());
        assertEquals("alice@example.com", client.email());
        assertEquals("VIP client",     client.notes());
        assertFalse(client.archived());
        assertEquals(NOW, client.createdAt());
        assertEquals(NOW, client.updatedAt());
    }

    @Test
    @DisplayName("null email is allowed")
    void should_allow_null_email() {
        assertDoesNotThrow(() ->
                new Client(ID, "Bob", "+22670000002", null, null, false, NOW, NOW));
    }

    @Test
    @DisplayName("blank email is allowed (treated as absent)")
    void should_allow_blank_email() {
        assertDoesNotThrow(() ->
                new Client(ID, "Bob", "+22670000002", "  ", null, false, NOW, NOW));
    }

    @Test
    @DisplayName("null notes is allowed")
    void should_allow_null_notes() {
        assertDoesNotThrow(() ->
                new Client(ID, "Bob", "+22670000002", null, null, false, NOW, NOW));
    }

    // ── Name validation ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("name validation")
    class NameValidation {

        @Test
        @DisplayName("null name throws VALIDATION_ERROR")
        void should_throw_on_null_name() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Client(ID, null, "+22670000001", null, null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("blank name throws VALIDATION_ERROR")
        void should_throw_on_blank_name() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Client(ID, "   ", "+22670000001", null, null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }
    }

    // ── Phone validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("phone validation (E.164)")
    class PhoneValidation {

        @Test
        @DisplayName("null phone throws VALIDATION_ERROR")
        void should_throw_on_null_phone() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Client(ID, "Alice", null, null, null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("phone without leading + throws VALIDATION_ERROR")
        void should_throw_on_phone_without_plus() {
            assertThrows(DomainException.class,
                    () -> new Client(ID, "Alice", "0022670000001", null, null, false, NOW, NOW));
        }

        @Test
        @DisplayName("phone too short throws VALIDATION_ERROR")
        void should_throw_on_phone_too_short() {
            assertThrows(DomainException.class,
                    () -> new Client(ID, "Alice", "+123456", null, null, false, NOW, NOW));
        }

        @Test
        @DisplayName("valid Burkina Faso number +226XXXXXXXX passes")
        void should_accept_valid_bf_phone() {
            assertDoesNotThrow(() ->
                    new Client(ID, "Alice", "+22670123456", null, null, false, NOW, NOW));
        }
    }

    // ── Email validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("email validation (optional)")
    class EmailValidation {

        @Test
        @DisplayName("email without @ throws VALIDATION_ERROR")
        void should_throw_on_invalid_email_format() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Client(ID, "Alice", "+22670000001", "invalid-email", null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("valid email with @ is accepted")
        void should_accept_valid_email() {
            assertDoesNotThrow(() ->
                    new Client(ID, "Alice", "+22670000001", "alice@keevo.app", null, false, NOW, NOW));
        }
    }
}

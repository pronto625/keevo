package com.keevo.catalog.contact.domain.entity;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for the Supplier domain entity (Story 2.5).
 */
@DisplayName("Supplier domain entity")
class SupplierTest {

    private static final UUID ID    = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid supplier with all fields constructs successfully")
    void should_construct_with_all_fields() {
        Supplier supplier = new Supplier(ID, "Fournisseur SA", "+22670000099",
                "contact@fournisseur.com", false, NOW, NOW);

        assertEquals(ID,                       supplier.id());
        assertEquals("Fournisseur SA",         supplier.name());
        assertEquals("+22670000099",           supplier.phone());
        assertEquals("contact@fournisseur.com", supplier.email());
        assertFalse(supplier.archived());
        assertEquals(NOW, supplier.createdAt());
        assertEquals(NOW, supplier.updatedAt());
    }

    @Test
    @DisplayName("null email is allowed")
    void should_allow_null_email() {
        assertDoesNotThrow(() ->
                new Supplier(ID, "Fournisseur SA", "+22670000099", null, false, NOW, NOW));
    }

    @Test
    @DisplayName("blank email is allowed (treated as absent)")
    void should_allow_blank_email() {
        assertDoesNotThrow(() ->
                new Supplier(ID, "Fournisseur SA", "+22670000099", "  ", false, NOW, NOW));
    }

    // ── Name validation ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("name validation")
    class NameValidation {

        @Test
        @DisplayName("null name throws VALIDATION_ERROR")
        void should_throw_on_null_name() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Supplier(ID, null, "+22670000099", null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("blank name throws VALIDATION_ERROR")
        void should_throw_on_blank_name() {
            DomainException ex = assertThrows(DomainException.class,
                    () -> new Supplier(ID, "   ", "+22670000099", null, false, NOW, NOW));
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
                    () -> new Supplier(ID, "Fournisseur SA", null, null, false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("phone without leading + throws VALIDATION_ERROR")
        void should_throw_on_phone_without_plus() {
            assertThrows(DomainException.class,
                    () -> new Supplier(ID, "Fournisseur SA", "0022670000001", null, false, NOW, NOW));
        }

        @Test
        @DisplayName("valid phone in E.164 format passes")
        void should_accept_valid_e164_phone() {
            assertDoesNotThrow(() ->
                    new Supplier(ID, "Fournisseur SA", "+33612345678", null, false, NOW, NOW));
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
                    () -> new Supplier(ID, "Fournisseur SA", "+22670000099", "not-an-email", false, NOW, NOW));
            assertEquals("VALIDATION_ERROR", ex.getDomainCode());
        }

        @Test
        @DisplayName("valid email with @ is accepted")
        void should_accept_valid_email() {
            assertDoesNotThrow(() ->
                    new Supplier(ID, "Fournisseur SA", "+22670000099", "info@supply.biz", false, NOW, NOW));
        }
    }
}

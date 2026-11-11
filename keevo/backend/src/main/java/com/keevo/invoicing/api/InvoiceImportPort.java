package com.keevo.invoicing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.4 — document writes of the Bref migration tool (analysis §10.2 step 7): quotes and invoices inserted as they
 * were in Bref, with their lines and payments, never through {@code InvoiceLifecycleUseCase.issue} (no numbering
 * counter, no quota, no event, no notification). Idempotent ({@code ON CONFLICT (id) DO NOTHING}), set-based (one
 * statement per table), joining the caller's transaction; the tenant context must be bound to {@code tenantSchema}.
 * Every value is checked against the rules a stored document must meet to be read back: an invalid one throws
 * {@link IllegalArgumentException} before any write.
 */
public interface InvoiceImportPort {

    /**
     * The seller profile of an imported tenant ({@code invoicing_settings}: this identity, the VAT rate of the
     * country of the oldest active store, not VAT-registered, prices without VAT — Bref's way) and its document
     * preferences (public links valid {@code DocumentPreferences.IMPORTED_LINK_DAYS} days, Bref's lifetime), each
     * written only when absent: an owner's later choice is never overwritten.
     */
    void importSettings(String tenantSchema, Seller seller, Instant at);

    /**
     * Inserts the documents, lines and payments whose id is absent; then computes the collection state
     * ({@code payment_state}) of every invoice concerned from its stored rows (an invoice PAID stays PAID; OVERDUE is
     * set directly, so the daily job notifies nothing), and records the old Bref public links of the documents given
     * ({@code legacy_public_token} on the rows, {@code public.invoice_legacy_links} for the redirection).
     *
     * @param today the civil day (Africa/Douala) the overdue rule compares due dates with
     * @return the ids inserted by this call, per table
     */
    Result importDocuments(String tenantSchema, List<Document> documents, List<Line> lines, List<Payment> payments,
                           LocalDate today);

    /** A seller identity (the header printed on a document). */
    record Seller(String legalName, String address, String phone, String niu, String rccm) {
    }

    /**
     * A document to import.
     *
     * @param quote             {@code true}: a quote (status DRAFT or CANCELLED, no number, no issue date)
     * @param status            {@code DRAFT} | {@code ISSUED} | {@code PAID} | {@code CANCELLED}
     * @param number            the invoice number ({@code BRF-…}), {@code null} for a quote
     * @param vatRate           percent, 2 decimals, the rate applied (0 without VAT)
     * @param legacyToken       Bref's public token, {@code null} when none (or when the document is deleted)
     * @param legacyReference   {@code BRF-XXXXXXXX}
     * @param legacyNotes       Bref's free-text notes, kept verbatim
     */
    record Document(UUID id, boolean quote, String status, String number, LocalDate issueDate, UUID clientId,
                    String clientName, String clientPhone, String clientEmail, String clientNiu, String clientRccm,
                    String clientAddress, String clientContactName, String clientWebsite, Seller seller,
                    boolean vatRegistered, BigDecimal vatRate, long subtotal, long discount, long deliveryFee,
                    long totalHt, long totalVat, long totalTtc, Instant issuedAt, Instant paidAt, String paymentMethod,
                    Instant cancelledAt, String cancellationReason, UUID createdBy, UUID storeId, LocalDate dueDate,
                    UUID legacyToken, Instant legacyTokenExpiresAt, String legacyReference, String legacyNotes,
                    Instant createdAt, Instant updatedAt, Instant deletedAt) {

        public Document {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(clientName, "clientName");
            Objects.requireNonNull(vatRate, "vatRate");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public String toString() {
            return "Document[" + id + "]"; // no personal data in logs
        }
    }

    /** A document line (position from 1, amounts in francs). */
    record Line(UUID id, UUID documentId, int position, String description, BigDecimal quantity, long unitPrice,
                BigDecimal vatRate, long totalHt, long totalVat, long totalTtc, UUID productId, String reference) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(vatRate, "vatRate");
        }

        @Override
        public String toString() {
            return "Line[" + id + "]";
        }
    }

    /** A payment received ({@code paidAt}) or an instalment due ({@code dueDate}); amount in francs, {@code > 0}. */
    record Payment(UUID id, UUID documentId, long amount, LocalDate dueDate, Instant paidAt, String method,
                   String note, UUID createdBy, Instant createdAt, Instant updatedAt) {

        public Payment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public String toString() {
            return "Payment[" + id + "]";
        }
    }

    /** The ids inserted by one call. */
    record Result(Set<UUID> documents, Set<UUID> lines, Set<UUID> payments) {

        public Result {
            documents = Set.copyOf(documents);
            lines = Set.copyOf(lines);
            payments = Set.copyOf(payments);
        }
    }
}

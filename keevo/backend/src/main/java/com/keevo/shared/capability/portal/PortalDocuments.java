package com.keevo.shared.capability.portal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — the documents shown to an end client in the client portal, read each in its own tenant schema, with a
 * short-lived signed link to the public PDF. Implemented by {@code invoicing}; the caller has already decided which
 * tenants may show their documents (tenant active, portal enabled).
 *
 * <p>Reprise 11/11/2026 — what Bref's « mes factures » screens show, so the app needs no other call: the merchant's
 * phone, the lines, the collection (paid, remaining, payments and instalments) and, on the first page, the client's
 * totals ({@link #summarize}). Still no identifier, no client data, no internal note.
 */
public interface PortalDocuments {

    /** Validity of a link handed out by the portal (re-signed at every listing). */
    java.time.Duration LINK_VALIDITY = java.time.Duration.ofHours(24);

    /**
     * Reads the invoices {@code idsBySchema}: the documents in one statement, then their lines and their payments in
     * one statement each. Only non-deleted invoices (not quotes, not replaced after a sale correction) that are
     * issued, paid or cancelled are returned; anything else (unknown id, draft, quote, deleted) is absent. Order is
     * not significant (the caller orders by its index).
     *
     * @param idsBySchema tenant schema → invoice ids (schemas validated by the implementation)
     * @param baseUrl     absolute base of the links ({@code https://keevo.ad2s.cm})
     */
    List<PortalDocument> find(Map<String, ? extends Collection<UUID>> idsBySchema, String baseUrl);

    /**
     * The client's totals over every invoice indexed for {@code phone} ({@code public.invoice_phone_index}, the shared
     * index of this capability) at the merchants {@code tenantIdBySchema} (the caller's visible ones), same filter as
     * {@link #find}, in one statement: how many are listed, and the sum of the totals of those not cancelled. Exact
     * (no cap) and no id travels to the application.
     *
     * @param tenantIdBySchema tenant schema → tenant id (schemas validated by the implementation)
     */
    Summary summarize(Map<String, UUID> tenantIdBySchema, String phone);

    /**
     * What the portal shows of one invoice. {@code tenantSchema} and {@code invoiceId} are keys for the caller, never
     * shown to the client.
     *
     * @param merchantName      seller name printed on the invoice
     * @param merchantPhone     seller phone printed on the invoice, null when none
     * @param reference         {@code FAC-2026-000001}, or the Bref reference of an imported invoice
     * @param amountPaid        sum of the payments received (the amount due for an invoice paid before 20.4)
     * @param amountRemaining   what the client still owes (total less credit notes less paid, never below 0; 0 when
     *                          cancelled)
     * @param paymentState      {@code UNPAID | PARTIAL | PAID | OVERDUE | CANCELLED}
     * @param paymentStateLabel French label of {@code paymentState}
     * @param lines             the invoice lines, in their printed order
     * @param payments          payments received and instalments, by date
     * @param linkUrl           signed link to the PDF, null for a cancelled invoice
     * @param linkExpiresAt     when {@code linkUrl} stops opening, null without a link
     */
    record PortalDocument(String tenantSchema, UUID invoiceId, String merchantName, String merchantPhone,
                          String reference, LocalDate issueDate, LocalDate dueDate, long totalTtc, long amountPaid,
                          long amountRemaining, String paymentState, String paymentStateLabel, List<Line> lines,
                          List<Payment> payments, String linkUrl, Instant linkExpiresAt) {

        public PortalDocument {
            lines = lines == null ? List.of() : List.copyOf(lines);
            payments = payments == null ? List.of() : List.copyOf(payments);
        }
    }

    /**
     * One printed line.
     *
     * @param unitPrice unit price as typed (excluding VAT when the invoice's prices exclude it)
     * @param totalTtc  line total including VAT
     */
    record Line(String reference, String description, BigDecimal quantity, long unitPrice, long totalTtc) {
    }

    /**
     * A payment received ({@code paid}, {@code paidDate}) or an instalment ({@code dueDate}, not paid yet — a settled
     * instalment keeps its due date).
     *
     * @param method how it was paid, as the merchant typed it (null when not said)
     */
    record Payment(long amount, LocalDate dueDate, boolean paid, LocalDate paidDate, String method) {
    }

    /** The client's totals: invoices listed, and the sum of those not cancelled (XAF). */
    record Summary(long invoiceCount, long totalTtc) {

        public static final Summary EMPTY = new Summary(0, 0);

        public Summary plus(Summary other) {
            return new Summary(invoiceCount + other.invoiceCount, Math.addExact(totalTtc, other.totalTtc));
        }
    }
}

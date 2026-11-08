package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invoice — quotes: reference, guards, cancellation, conversion (Story 20.3)")
class InvoiceQuoteTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-06T10:00:00Z");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa, Douala",
            "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true, null, null);
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", null, null, "+237690000001");
    private static final List<LineInput> LINES = List.of(new LineInput("Sac de riz 25 kg", BigDecimal.ONE, 11_925,
            VatTreatment.STANDARD));
    private static final UUID QUOTE_ID = UUID.fromString("3f0c6a52-6d1e-4b7a-9c55-2a8e0f1b7d44");

    private static Invoice quote() {
        return Invoice.quote(QUOTE_ID, CLIENT, LINES, SELLER, UUID.randomUUID(), T0);
    }

    private static DomainException domain(Throwable t) {
        return (DomainException) t;
    }

    @Test
    @DisplayName("quote reference: DV- + first 8 hex chars upper case; computed, never for an invoice")
    void reference() {
        assertThat(InvoiceKind.quoteReference(UUID.fromString("ab12cd34-0000-4000-8000-000000000000")))
                .isEqualTo("DV-AB12CD34");
        assertThat(quote().quoteReference()).isEqualTo("DV-3F0C6A52");
        assertThat(Invoice.draft(QUOTE_ID, CLIENT, LINES, SELLER, null, T0).quoteReference()).isNull();
        assertThat(InvoiceKind.quoteReference(null)).isNull();
    }

    @Test
    @DisplayName("a quote is a computed DRAFT of kind QUOTE: no number, no issue date, no seller snapshot")
    void created() {
        Invoice q = quote();
        assertThat(q.kind()).isEqualTo(InvoiceKind.QUOTE);
        assertThat(q.isQuote()).isTrue();
        assertThat(q.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(q.number()).isNull();
        assertThat(q.issueDate()).isNull();
        assertThat(q.seller()).isNull();
        assertThat(q.totals()).isEqualTo(new InvoiceTotals(10_000, 1_925, 11_925));
    }

    @Test
    @DisplayName("an edit keeps the kind; a legacy invoice (no kind) is an INVOICE")
    void editKeepsKind() {
        Invoice edited = quote().withDraftContent(CLIENT, List.of(new LineInput("Huile", new BigDecimal("2"), 1_000,
                VatTreatment.EXEMPT)), SELLER, T1);
        assertThat(edited.kind()).isEqualTo(InvoiceKind.QUOTE);
        assertThat(edited.totals()).isEqualTo(new InvoiceTotals(2_000, 0, 2_000));
        assertThat(edited.updatedAt()).isEqualTo(T1);
        assertThat(Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0).kind())
                .isEqualTo(InvoiceKind.INVOICE);
    }

    @Test
    @DisplayName("issue / pay / credit note on a quote → INVOICE_INVALID_TRANSITION « Un devis doit être "
            + "converti en facture » (Story 20.7: sending is allowed)")
    void invoiceOnlyActionsRefused() {
        Invoice q = quote();
        for (Runnable action : List.<Runnable>of(
                () -> q.issue("FAC-2026-000001", LocalDate.of(2026, 11, 6), SELLER, T1),
                () -> q.assertIssuable(SELLER),
                () -> q.markedPaid(T1, "Espèces", T1),
                () -> CreditNote.creditLines(q, List.of(), null))) {
            assertThatThrownBy(action::run).isInstanceOf(DomainException.class).satisfies(e -> {
                assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION");
                assertThat(domain(e).getUserMessage()).isEqualTo("Un devis doit être converti en facture");
            });
        }
    }

    @Test
    @DisplayName("Story 20.7 — a quote waiting for validation is sendable (reference DV-…); converted / cancelled → 409")
    void quoteSending() {
        Invoice q = quote();
        q.assertSendable();
        assertThat(q.documentReference()).isEqualTo(q.quoteReference()).startsWith("DV-");
        for (Invoice ended : List.of(q.cancel("Le client a renoncé", T1), q.converted(UUID.randomUUID(), T1))) {
            assertThatThrownBy(ended::assertSendable).isInstanceOf(DomainException.class)
                    .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
        }
    }

    @Test
    @DisplayName("cancel a DRAFT quote: CANCELLED, reason kept, no number; reason required; twice → refused")
    void cancel() {
        Invoice cancelled = quote().cancel("Le client a renoncé", T1);
        assertThat(cancelled.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.kind()).isEqualTo(InvoiceKind.QUOTE);
        assertThat(cancelled.number()).isNull();
        assertThat(cancelled.cancelledAt()).isEqualTo(T1);
        assertThat(cancelled.cancellationReason()).isEqualTo("Le client a renoncé");
        assertThat(cancelled.status().label(cancelled.kind())).isEqualTo("Annulé");

        assertThatThrownBy(() -> quote().cancel("  ", T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> cancelled.cancel("encore", T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(cancelled::assertEditable)
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_NOT_EDITABLE"));
        assertThatThrownBy(cancelled::assertConvertible)
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("conversion draft: a new INVOICE draft with the quote's client and lines, linked by sourceQuoteId")
    void conversionDraft() {
        UUID actor = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice draft = quote().conversionDraft(invoiceId, SELLER, actor, T1);
        assertThat(draft.id()).isEqualTo(invoiceId);
        assertThat(draft.kind()).isEqualTo(InvoiceKind.INVOICE);
        assertThat(draft.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(draft.sourceQuoteId()).isEqualTo(QUOTE_ID);
        assertThat(draft.client()).isEqualTo(CLIENT);
        assertThat(draft.lines()).isEqualTo(quote().lines());
        assertThat(draft.createdBy()).isEqualTo(actor);
        // and it issues like any invoice; the link survives the issue
        Invoice issued = draft.issue("FAC-2026-000007", LocalDate.of(2026, 11, 6), SELLER, T1);
        assertThat(issued.number()).isEqualTo("FAC-2026-000007");
        assertThat(issued.sourceQuoteId()).isEqualTo(QUOTE_ID);
    }

    @Test
    @DisplayName("converted: CONVERTED with the link; then not editable, not convertible again, not cancellable")
    void converted() {
        UUID invoiceId = UUID.randomUUID();
        Invoice converted = quote().converted(invoiceId, T1);
        assertThat(converted.status()).isEqualTo(InvoiceStatus.CONVERTED);
        assertThat(converted.convertedInvoiceId()).isEqualTo(invoiceId);
        assertThat(converted.convertedAt()).isEqualTo(T1);
        assertThat(converted.updatedAt()).isEqualTo(T1);
        assertThat(converted.number()).isNull();
        assertThat(converted.status().label(converted.kind())).isEqualTo("Converti");

        assertThatThrownBy(converted::assertEditable)
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_NOT_EDITABLE"));
        assertThatThrownBy(() -> converted.converted(UUID.randomUUID(), T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> converted.cancel("trop tard", T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("an invoice is never converted")
    void invoiceNotConvertible() {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0);
        assertThatThrownBy(() -> invoice.conversionDraft(UUID.randomUUID(), SELLER, null, T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));
        // an issued invoice stays non-editable
        Invoice issued = invoice.issue("FAC-2026-000001", LocalDate.of(2026, 11, 6), SELLER, T1);
        assertThatThrownBy(issued::assertEditable)
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("an incomplete quote is not convertible: « Ce devis est incomplet : … » (INVOICE_NOT_ISSUABLE)")
    void incomplete() {
        Invoice empty = Invoice.quote(QUOTE_ID, CLIENT, List.of(), SELLER, null, T0);
        assertThatThrownBy(empty::assertConvertible).satisfies(e -> {
            assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_NOT_ISSUABLE");
            assertThat(domain(e).getUserMessage()).isEqualTo("Ce devis est incomplet : ajoutez au moins une ligne");
        });
        assertThatThrownBy(() -> empty.conversionDraft(UUID.randomUUID(), SELLER, null, T1))
                .satisfies(e -> assertThat(domain(e).getDomainCode()).isEqualTo("INVOICE_NOT_ISSUABLE"));
    }

    @Test
    @DisplayName("20.14: the store is kept by every step — edit, conversion (the quote's store), issue, payment, "
            + "sending, cancellation")
    void storeKept() {
        UUID store = UUID.randomUUID();
        Invoice q = quote().inStore(store);
        assertThat(q.withDraftContent(CLIENT, LINES, SELLER, T1).storeId()).isEqualTo(store);
        assertThat(q.converted(UUID.randomUUID(), T1).storeId()).isEqualTo(store);
        assertThat(q.cancel("Client parti", T1).storeId()).isEqualTo(store);

        Invoice draft = q.conversionDraft(UUID.randomUUID(), SELLER, UUID.randomUUID(), T1);
        assertThat(draft.storeId()).isEqualTo(store);
        Invoice issued = draft.issue("FAC-2026-000001", java.time.LocalDate.of(2026, 11, 6), SELLER, T1);
        assertThat(issued.storeId()).isEqualTo(store);
        assertThat(issued.sent("+237690000001", T1).storeId()).isEqualTo(store);
        assertThat(issued.markedPaid(T1, "Espèces", T1).storeId()).isEqualTo(store);
        assertThat(issued.cancel("Erreur", T1).storeId()).isEqualTo(store);
        assertThat(Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0).storeId()).isNull();
    }

    @Test
    @DisplayName("20.14: the store is fixed at creation — another store, or a store set after the draft, is refused "
            + "by the domain; the same store again is a no-op")
    void storeFixedAtCreation() {
        UUID store = UUID.randomUUID();
        Invoice q = quote().inStore(store);
        assertThat(q.inStore(store)).isSameAs(q);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> q.inStore(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> q.inStore(null))
                .isInstanceOf(IllegalStateException.class);
        Invoice cancelled = quote().cancel("Client parti", T1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cancelled.inStore(store))
                .isInstanceOf(IllegalStateException.class);
    }
}

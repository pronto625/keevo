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

@DisplayName("Invoice — lifecycle and snapshot (Story 14.1)")
class InvoiceTest {

    private static final Instant T0 = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-30T10:00:00Z");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa, Douala",
            "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true,
            "MTN MoMo 690000000", "Merci de votre confiance");
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", "P098765432109Z", null, null);
    private static final List<LineInput> LINES = List.of(new LineInput("Sac de riz 25 kg", BigDecimal.ONE, 11_925,
            VatTreatment.STANDARD));

    private static Invoice draft() {
        return Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, UUID.randomUUID(), T0);
    }

    private static String code(Throwable t) {
        return ((DomainException) t).getDomainCode();
    }

    @Test
    @DisplayName("a draft is computed, has no number, no seller snapshot")
    void draftComputed() {
        Invoice d = draft();
        assertThat(d.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(d.number()).isNull();
        assertThat(d.seller()).isNull();
        assertThat(d.totals()).isEqualTo(new InvoiceTotals(10_000, 1_925, 11_925));
        assertThat(d.vatMention()).isNull();
    }

    @Test
    @DisplayName("a draft is recomputed on change; an empty client name is refused")
    void draftChange() {
        Invoice d = draft().withDraftContent(CLIENT, List.of(new LineInput("Huile", new BigDecimal("2"), 1_000,
                VatTreatment.EXEMPT)), SELLER, T1);
        assertThat(d.totals()).isEqualTo(new InvoiceTotals(2_000, 0, 2_000));
        assertThat(d.updatedAt()).isEqualTo(T1);
        assertThat(d.createdAt()).isEqualTo(T0);
        assertThatThrownBy(() -> Invoice.draft(UUID.randomUUID(), new ClientInfo(null, " ", null, null, null), LINES,
                SELLER, null, T0)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("issue: number, date, frozen seller snapshot — later settings edits do not change it")
    void issueSnapshots() {
        Invoice issued = draft().issue("FAC-2026-000001", LocalDate.of(2026, 10, 30), SELLER, T1);
        assertThat(issued.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(issued.number()).isEqualTo("FAC-2026-000001");
        assertThat(issued.issueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(issued.issuedAt()).isEqualTo(T1);
        assertThat(issued.seller()).isEqualTo(SellerSnapshot.of(SELLER));
        assertThat(issued.complianceWarnings(InvoicingSettings.defaults())).isEmpty();
        assertThatThrownBy(() -> issued.withDraftContent(CLIENT, LINES, SELLER, T1))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_NOT_EDITABLE"));
        assertThatThrownBy(issued::assertEditable).satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("issue recomputes with the settings of the issue (not VAT-registered → « TVA non applicable »)")
    void issueRecomputes() {
        InvoicingSettings notRegistered = new InvoicingSettings("Awa", null, null, null, null, false,
                new BigDecimal("19.25"), true, null, null);
        Invoice issued = draft().issue("FAC-2026-000002", LocalDate.of(2026, 10, 30), notRegistered, T1);
        assertThat(issued.vatMention()).isEqualTo("TVA non applicable");
        assertThat(issued.totals()).isEqualTo(new InvoiceTotals(11_925, 0, 11_925));
        assertThat(issued.complianceWarnings(null)).hasSize(3)
                .anySatisfy(w -> assertThat(w).startsWith("Le NIU de votre commerce n'est pas renseigné"))
                .anySatisfy(w -> assertThat(w).contains("RCCM"))
                .anySatisfy(w -> assertThat(w).contains("adresse"));
    }

    @Test
    @DisplayName("not issuable: no line, no seller legal name → INVOICE_NOT_ISSUABLE with a French reason")
    void notIssuable() {
        Invoice empty = Invoice.draft(UUID.randomUUID(), CLIENT, List.of(), SELLER, null, T0);
        assertThatThrownBy(() -> empty.issue("x", LocalDate.now(), SELLER, T1))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo("INVOICE_NOT_ISSUABLE");
                    assertThat(((DomainException) e).getDetails().get("reason").toString())
                            .contains("ajoutez au moins une ligne");
                });
        assertThatThrownBy(() -> draft().issue("x", LocalDate.now(), InvoicingSettings.defaults(), T1))
                .satisfies(e -> assertThat(((DomainException) e).getDetails().get("reason").toString())
                        .contains("nom de votre commerce"));
    }

    @Test
    @DisplayName("transitions: ISSUED → PAID, ISSUED → CANCELLED (reason); anything else 409")
    void transitions() {
        Invoice issued = draft().issue("FAC-2026-000001", LocalDate.of(2026, 10, 30), SELLER, T1);
        Invoice paid = issued.pay(" Espèces ", T1);
        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.paidAt()).isEqualTo(T1);
        assertThat(paid.paymentMethod()).isEqualTo("Espèces");
        assertThat(paid.number()).isEqualTo("FAC-2026-000001");

        Invoice cancelled = issued.cancel("Erreur de client", T1);
        assertThat(cancelled.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.number()).isEqualTo("FAC-2026-000001");
        assertThat(cancelled.cancellationReason()).isEqualTo("Erreur de client");

        assertThatThrownBy(() -> paid.cancel("x", T1)).satisfies(e -> {
            assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION");
            assertThat(((DomainException) e).getDetails()).containsEntry("status", "payée");
        });
        assertThatThrownBy(() -> issued.cancel("  ", T1)).satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> draft().pay(null, T1)).satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> draft().cancel("x", T1)).satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> paid.pay(null, T1)).satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> cancelled.pay(null, T1)).satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> issued.issue("y", LocalDate.now(), SELLER, T1))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("assertIssuable: not a draft → INVALID_TRANSITION; blockers → NOT_ISSUABLE; issuable → no error")
    void assertIssuable() {
        draft().assertIssuable(SELLER);
        assertThatThrownBy(() -> draft().assertIssuable(InvoicingSettings.defaults()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_NOT_ISSUABLE"));
        Invoice issued = draft().issue("FAC-2026-000001", LocalDate.of(2026, 10, 30), SELLER, T1);
        assertThatThrownBy(() -> issued.assertIssuable(SELLER))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("a VAT-registered seller with a 0 % rate is refused")
    void vatRegisteredNeedsRate() {
        assertThatThrownBy(() -> new InvoicingSettings("Awa", null, null, null, null, true, BigDecimal.ZERO, true,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InvoicingSettings("Awa", null, null, null, null, false, BigDecimal.ZERO, true, null, null)
                .vatRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("draft warnings come from the current settings")
    void draftWarnings() {
        assertThat(draft().complianceWarnings(SELLER)).isEmpty();
        assertThat(draft().complianceWarnings(InvoicingSettings.defaults())).hasSize(3);
    }
}

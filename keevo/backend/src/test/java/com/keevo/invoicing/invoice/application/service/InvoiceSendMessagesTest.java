package com.keevo.invoicing.invoice.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InvoiceSendMessages — Bref SMS / email texts with the public link (Story 20.8)")
class InvoiceSendMessagesTest {

    /** A signed link as long as a real one (≈ 170 characters). */
    private static final String LINK = "https://keevo.ad2s.cm/api/v1/invoices/shared?token="
            + "a2Zfc2Q0ZjVnfDNmMGM2YTUyLTZkMWUtNGI3YS05YzU1LTJhOGUwZjFiN2Q0NHwxNzY1MDAwMDAwfDE"
            + ".Zm9vYmFyYmF6cXV4Zm9vYmFyYmF6cXV4Zm9vYmFyYmF6cXU";

    @Test
    @DisplayName("SMS of an invoice: Bref wording, shop name, link intact, GSM-7, ≤ 306 characters")
    void smsInvoice() {
        String text = InvoiceSendMessages.sms(false, "FAC-2026-000001", "Boutique Awa SARL", LINK);
        assertThat(text).isEqualTo("Facture FAC-2026-000001 - Boutique Awa SARL\nMerci pour votre achat !\n"
                + "Votre facture : " + LINK);
        assertThat(text.length()).isLessThanOrEqualTo(InvoiceSendMessages.SMS_MAX);
    }

    @Test
    @DisplayName("SMS of a quote: Bref wording « Nous revenons vers vous pour confirmer votre commande »")
    void smsQuote() {
        assertThat(InvoiceSendMessages.sms(true, "DV-1A2B3C4D", "Boutique Awa", LINK)).isEqualTo(
                "Devis DV-1A2B3C4D - Boutique Awa\nMerci ! Nous revenons vers vous pour confirmer votre commande.\n"
                        + "Votre devis : " + LINK);
    }

    @Test
    @DisplayName("SMS: accents and typographic signs made GSM-7 (no UCS-2 switch); extension characters replaced")
    void smsGsm7() {
        String text = InvoiceSendMessages.sms(false, "FAC-2026-000002", "Épicerie « Chez Hélène » — Ndogbong [2]",
                LINK);
        assertThat(text).startsWith("Facture FAC-2026-000002 - Epicerie \" Chez Helene \" - Ndogbong -2-\n");
        assertThat(text).matches("[\\n\\x20-\\x7E]+");
    }

    @Test
    @DisplayName("SMS: a long shop name is shortened first, then dropped, then the thanks line; reference and link "
            + "never cut")
    void smsLength() {
        String longName = "Etablissements Commerciaux Generaux de Distribution Alimentaire et Quincaillerie du Littoral";
        String text = InvoiceSendMessages.sms(true, "DV-1A2B3C4D", longName, LINK);
        assertThat(text.length()).isEqualTo(InvoiceSendMessages.SMS_MAX);
        assertThat(text).startsWith("Devis DV-1A2B3C4D - Etablissements").endsWith("Votre devis : " + LINK);
        assertThat(text).contains(".\nMerci ! Nous revenons");

        String longLink = LINK + "x".repeat(60);
        String noShop = InvoiceSendMessages.sms(false, "FAC-2026-000003", "Boutique Awa", longLink);
        assertThat(noShop).isEqualTo("Facture FAC-2026-000003\nMerci pour votre achat !\nVotre facture : " + longLink);

        String bare = InvoiceSendMessages.sms(false, "FAC-2026-000003", "Boutique Awa", LINK + "x".repeat(100));
        // no room for the whole reference: shortened, the link kept whole, ≤ 306
        assertThat(bare).isEqualTo("Facture FA.\nVotre facture : " + LINK + "x".repeat(100));
        assertThat(bare.length()).isLessThanOrEqualTo(InvoiceSendMessages.SMS_MAX);
    }

    @Test
    @DisplayName("Email: Bref subject / text / HTML card with the link, status, amount, shop header; values escaped")
    void email() {
        InvoiceSendMessages.Email e = InvoiceSendMessages.email(new InvoiceSendMessages.EmailContent(false,
                "FAC-2026-000001", "Awa <Traoré>", "Boutique \"Awa\" & Fils", "Rue Joss, Douala", "+237699000000",
                28_850, "Partiellement payée", LINK, 30));
        assertThat(e.subject()).isEqualTo("Votre facture FAC-2026-000001 — Boutique \"Awa\" & Fils");
        assertThat(e.text()).isEqualTo("Bonjour Awa <Traoré>,\n\nVeuillez trouver ci-joint votre facture "
                + "FAC-2026-000001 de Boutique \"Awa\" & Fils.\n\nMontant : 28 850 XAF\n\n"
                + "Consultez votre facture en ligne : " + LINK + "\n\nMerci de votre confiance.");
        assertThat(e.html()).contains("Bonjour <strong>Awa &lt;Traoré&gt;</strong>",
                "Boutique &quot;Awa&quot; &amp; Fils", "N° FACTURE", "FAC-2026-000001", "STATUT",
                "Partiellement payée", "MONTANT TOTAL", "28 850 XAF", "Rue Joss, Douala", "+237699000000",
                "href=\"" + LINK + "\"", "Voir la facture en ligne", "La facture PDF est jointe à cet email.",
                "valable 30 jours")
                .doesNotContain("<Traoré>");
    }

    @Test
    @DisplayName("Email of a quote without shop address: « devis », no empty header line")
    void emailQuote() {
        InvoiceSendMessages.Email e = InvoiceSendMessages.email(new InvoiceSendMessages.EmailContent(true,
                "DV-1A2B3C4D", "Société Ndolo", "Boutique Awa", null, null, 23_850, "En attente de validation", LINK,
                90));
        assertThat(e.subject()).isEqualTo("Votre devis DV-1A2B3C4D — Boutique Awa");
        assertThat(e.text()).contains("votre devis DV-1A2B3C4D de Boutique Awa", "Consultez votre devis en ligne");
        assertThat(e.html()).contains("N° DEVIS", "En attente de validation", "valable 90 jours",
                        "Voir le devis en ligne", "Le devis PDF est joint à cet email.")
                .doesNotContain("color:#F4A01C;font-size:12px;");
    }

    @Test
    @DisplayName("Amounts with plain-space thousands")
    void amount() {
        assertThat(InvoiceSendMessages.amount(28_850)).isEqualTo("28 850");
        assertThat(InvoiceSendMessages.amount(1_234_567)).isEqualTo("1 234 567");
        assertThat(InvoiceSendMessages.amount(-500)).isEqualTo("-500");
    }

    @Test
    @DisplayName("SMS: the backtick (not GSM-7) becomes an apostrophe; an abnormal reference is shortened, the link "
            + "kept whole, ≤ 306")
    void smsEdgeCases() {
        assertThat(InvoiceSendMessages.sms(false, "FAC-1", "Chez `Awa`", LINK)).startsWith("Facture FAC-1 - Chez 'Awa'");
        String longRef = "BRF-" + "X".repeat(200);
        String text = InvoiceSendMessages.sms(false, longRef, "Boutique", LINK);
        assertThat(text.length()).isLessThanOrEqualTo(InvoiceSendMessages.SMS_MAX);
        assertThat(text).startsWith("Facture BRF-XXX").endsWith("Votre facture : " + LINK);
        String hugeLink = LINK + "y".repeat(400);
        assertThat(InvoiceSendMessages.sms(false, "FAC-1", "B", hugeLink)).endsWith(hugeLink); // warned, link whole
    }
}

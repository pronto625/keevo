package com.keevo.invoicing.invoice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;

/**
 * Story 20.8 (FUS-FR22) — the French texts of a document sent by SMS or email, reproducing Bref's
 * ({@code api-bref/src/invoice/outbox/invoice-outbox.processor.ts}) with the Keevo reference ({@code FAC-…},
 * {@code DV-…}, {@code BRF-…}) instead of Bref's piece of UUID, and the signed public link (Story 20.7) instead of
 * Bref's app download link. Pure functions (no I/O): unit-tested for length and escaping.
 */
public final class InvoiceSendMessages {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSendMessages.class);

    /**
     * Longest SMS sent: 2 concatenated GSM-7 segments (2 × 153). Bref fits one segment (158) because its link is a
     * short download URL; the signed link alone is about 170 characters.
     */
    public static final int SMS_MAX = 306;

    private static final String INVOICE_THANKS = "\nMerci pour votre achat !";
    private static final String QUOTE_THANKS = "\nMerci ! Nous revenons vers vous pour confirmer votre commande.";

    /** Bref's HTML card (positional {@link String#format} arguments, see {@link #email}). */
    private static final String EMAIL_HTML = """
                    <!DOCTYPE html>
                    <html lang="fr">
                    <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
                    <body style="margin:0;padding:0;background:#f4f4f4;font-family:Arial,sans-serif;">
                      <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f4;padding:30px 0;">
                        <tr><td align="center">
                          <table width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:12px;overflow:hidden;max-width:600px;">
                            <tr><td style="background:#1A1A2E;padding:28px 36px;">
                              <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:bold;">%s</h1>%s%s
                            </td></tr>
                            <tr><td style="padding:36px 36px 28px;">
                              <p style="margin:0 0 20px;color:#444;font-size:15px;">Bonjour <strong>%s</strong>,</p>
                              <p style="margin:0 0 24px;color:#666;font-size:14px;">Veuillez trouver ci-joint votre %s de la boutique <strong>%s</strong>.</p>
                              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f9f9f9;border-radius:8px;overflow:hidden;margin-bottom:28px;">
                                <tr>
                                  <td style="padding:14px 20px;border-bottom:1px solid #eee;">
                                    <span style="color:#999;font-size:12px;">N° %s</span><br>
                                    <strong style="color:#1A1A2E;font-size:14px;">%s</strong>
                                  </td>
                                  <td style="padding:14px 20px;border-bottom:1px solid #eee;">
                                    <span style="color:#999;font-size:12px;">STATUT</span><br>
                                    <strong style="color:#1A1A2E;font-size:14px;">%s</strong>
                                  </td>
                                </tr>
                                <tr>
                                  <td colspan="2" style="padding:14px 20px;">
                                    <span style="color:#999;font-size:12px;">MONTANT TOTAL</span><br>
                                    <strong style="color:#1A1A2E;font-size:20px;">%s</strong>
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:0 0 24px;text-align:center;">
                                <a href="%s" style="display:inline-block;background:#F4A01C;color:#1A1A2E;font-weight:bold;font-size:14px;padding:12px 24px;border-radius:8px;text-decoration:none;">Voir %s en ligne</a>
                              </p>
                              <p style="margin:0 0 12px;color:#555;font-size:13px;">%s PDF est %s à cet email.</p>
                              <p style="margin:0;color:#888;font-size:12px;">Ce lien est valable %d jours.</p>
                            </td></tr>
                          </table>
                        </td></tr>
                      </table>
                    </body>
                    </html>
                    """;

    /** An email: subject, plain text, HTML. */
    public record Email(String subject, String text, String html) {
    }

    /**
     * What an email shows.
     *
     * @param statusLabel « En attente de validation », « Payée ✓ », « Partiellement payée », « En retard »,
     *                    « En attente de paiement »
     * @param linkDays    lifetime of the link, in days
     */
    public record EmailContent(boolean quote, String reference, String clientName, String shopName, String shopAddress,
                               String shopPhone, long totalTtc, String statusLabel, String link, int linkDays) {
    }

    private InvoiceSendMessages() {
    }

    /**
     * « Facture FAC-2026-000001 - Boutique Awa⏎Merci pour votre achat !⏎Votre facture : https://… » (quote: « Devis
     * DV-… - …⏎Merci ! Nous revenons vers vous pour confirmer votre commande.⏎Votre devis : https://… »), in GSM-7
     * (accents removed, as Bref's « Telechargez »), at most {@link #SMS_MAX} characters: the shop name is shortened
     * first, then dropped, then the thanks line, then an abnormally long reference is shortened; the link is never cut
     * (a link alone over the limit is sent whole, with a warning).
     */
    public static String sms(boolean quote, String reference, String shopName, String link) {
        String head = gsm((quote ? "Devis " : "Facture ") + reference);
        String thanks = quote ? QUOTE_THANKS : INVOICE_THANKS;
        String tail = gsm("\n" + (quote ? "Votre devis : " : "Votre facture : ")) + link;
        String shop = gsm(shopName == null ? "" : shopName.strip());

        int room = SMS_MAX - head.length() - thanks.length() - tail.length() - " - ".length();
        if (room >= shop.length() && !shop.isEmpty()) {
            return head + " - " + shop + thanks + tail;
        }
        if (room >= 4 && !shop.isEmpty()) {
            return head + " - " + shop.substring(0, room - 1).strip() + "." + thanks + tail;
        }
        if (head.length() + thanks.length() + tail.length() <= SMS_MAX) {
            return head + thanks + tail;
        }
        if (head.length() + tail.length() <= SMS_MAX) {
            return head + tail;
        }
        // an abnormally long reference: shortened, the link kept whole
        int headRoom = SMS_MAX - tail.length();
        if (headRoom >= 4) {
            return head.substring(0, headRoom - 1).strip() + "." + tail;
        }
        log.warn("INVOICE_SMS_TOO_LONG length={} — the link alone exceeds {} characters", head.length() + tail.length(),
                SMS_MAX);
        return head + tail;
    }

    /**
     * Bref's email: subject « Votre facture FAC-… — Boutique », text « Bonjour …, Veuillez trouver ci-joint votre
     * facture … Montant : … » and its HTML card (shop header, N° FACTURE / DEVIS, STATUT, MONTANT TOTAL), with the
     * public link instead of the app download link. Every value is HTML-escaped.
     */
    public static Email email(EmailContent c) {
        String label = c.quote() ? "devis" : "facture";
        String labelCap = c.quote() ? "DEVIS" : "FACTURE";
        String shop = c.shopName() == null || c.shopName().isBlank() ? "Boutique" : c.shopName().strip();
        String amount = amount(c.totalTtc()) + " XAF";
        String subject = "Votre " + label + " " + c.reference() + " — " + shop;
        String text = "Bonjour " + c.clientName() + ",\n\n"
                + "Veuillez trouver ci-joint votre " + label + " " + c.reference() + " de " + shop + ".\n\n"
                + "Montant : " + amount + "\n\n"
                + "Consultez votre " + label + " en ligne : " + c.link() + "\n\n"
                + "Merci de votre confiance.";
        String html = String.format(java.util.Locale.ROOT, EMAIL_HTML,
                escape(shop),
                blank(c.shopAddress()) ? "" : "\n          <p style=\"margin:4px 0 0;color:#F4A01C;font-size:12px;\">"
                        + escape(c.shopAddress()) + "</p>",
                blank(c.shopPhone()) ? "" : "\n          <p style=\"margin:4px 0 0;color:#aaa;font-size:11px;\">"
                        + escape(c.shopPhone()) + "</p>",
                escape(c.clientName()), label, escape(shop), labelCap, escape(c.reference()), escape(c.statusLabel()),
                escape(amount), escape(c.link()), c.quote() ? "le devis" : "la facture",
                c.quote() ? "Le devis" : "La facture", c.quote() ? "joint" : "jointe", c.linkDays());
        return new Email(subject, text, html);
    }

    /** {@code 28850} → {@code 28 850} (plain spaces). */
    public static String amount(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        int lead = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return value < 0 ? "-" + sb : sb.toString();
    }

    /**
     * Text an SMS carries in the GSM-7 basic set without escapes: accents removed, typographic quotes / dashes made
     * plain, ligatures spelled out; anything else outside printable ASCII becomes {@code ?}; GSM-7 extension
     * characters ({@code ^ { } [ ] ~ \ |}, counted twice) become {@code -}. Newlines kept.
     */
    static String gsm(String text) {
        if (text == null) {
            return "";
        }
        String plain = text.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE")
                .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
                .replace('«', '"').replace('»', '"').replace('–', '-').replace('—', '-')
                .replace(' ', ' ').replace(' ', ' ');
        String stripped = Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder(stripped.length());
        for (char ch : stripped.toCharArray()) {
            if (ch == '\n') {
                sb.append(ch);
            } else if ("^{}[]~\\|".indexOf(ch) >= 0) {
                sb.append('-');
            } else if (ch == '`') {
                sb.append('\''); // not in the GSM-7 basic set
            } else if (ch >= 0x20 && ch <= 0x7E) {
                sb.append(ch);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /** HTML text / attribute escaping ({@code & < > " '}); null → empty. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

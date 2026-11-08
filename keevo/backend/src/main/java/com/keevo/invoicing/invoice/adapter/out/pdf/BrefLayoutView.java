package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceTotals;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer.RenderRequest;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.pdf.PdfFormats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Story 20.7 — the one view model of the 8 Bref layouts (the equivalent of Bref's {@code InvoiceData} plus its
 * {@code FR_TRANSLATIONS}): every value pre-formatted here, written by the templates with escaped {@code th:text}
 * only. No identifier but the document's number / reference is ever printed.
 *
 * <p>Bref rules kept: the payment block ({@code renderPaymentSection}) — nothing for a quote nor for an invoice paid
 * at once, the cancellation reason for a cancelled invoice, else the payments and instalments, « Montant payé » and
 * « Reste à payer »; the classic layout highlights the amount still due of an unpaid invoice, else the total.
 */
final class BrefLayoutView {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private BrefLayoutView() {
    }

    /** An article line: every cell formatted. */
    public record Item(int index, String name, String reference, String quantity, String unitPrice, String total) {
    }

    /** A label / amount row of the totals or the payment block; {@code tone}: {@code ""}, {@code paid}, {@code strong}, {@code discount}, {@code void}. */
    public record Row(String label, String value, String tone) {
    }

    static Map<String, Object> variables(RenderRequest r) {
        Invoice invoice = r.invoice();
        SellerSnapshot seller = r.seller();
        Map<String, Object> v = new HashMap<>();

        // shop
        String shopName = text(seller == null ? null : seller.legalName());
        v.put("shopName", shopName == null ? "" : shopName);
        v.put("shopInitial", shopName == null || shopName.isEmpty() ? "" : shopName.substring(0, 1).toUpperCase(Locale.FRENCH));
        v.put("shopAddress", text(seller == null ? null : seller.address()));
        v.put("shopPhone", text(seller == null ? null : seller.phone()));
        v.put("shopNiu", text(seller == null ? null : seller.niu()));
        v.put("shopRccm", text(seller == null ? null : seller.rccm()));
        v.put("paymentDetails", text(seller == null ? null : seller.paymentDetails()));
        String footer = text(seller == null ? null : seller.footer());
        v.put("notes", footer);
        v.put("notesOrThanks", footer == null ? "Merci de votre confiance" : footer);
        v.put("logo", r.logo() == null ? null : r.logo().dataUri());

        // document
        boolean quote = invoice.isQuote();
        v.put("docTitle", quote ? "DEVIS" : "FACTURE");
        v.put("docLabel", quote ? "Devis" : "Facture");
        v.put("number", number(invoice, r.legacyReference()));
        LocalDate date = documentDate(invoice);
        Instant at = invoice.issuedAt() != null ? invoice.issuedAt() : invoice.createdAt();
        String time = at == null ? null : TIME.format(at.atZone(KeevoZones.WAT));
        v.put("date", LONG_DATE.format(date));
        v.put("dateTime", time == null ? LONG_DATE.format(date) : LONG_DATE.format(date) + " à " + time);
        v.put("dottedDateTime", time == null ? DOTTED_DATE.format(date) : DOTTED_DATE.format(date) + " · " + time);
        v.put("dueDate", invoice.terms().dueDate() == null ? null : LONG_DATE.format(invoice.terms().dueDate()));

        // client
        ClientInfo c = invoice.client();
        v.put("clientName", text(c.name()) == null ? "" : text(c.name()));
        v.put("clientPhone", text(c.phone()));
        v.put("clientEmail", text(c.email()));
        v.put("clientAddress", text(c.address()));
        v.put("clientNiu", text(c.niu()));
        v.put("clientRccm", text(c.rccm()));

        // lines: total = quantity × unit price as entered (HT or TTC per the document's pricing mode)
        List<Item> items = new ArrayList<>();
        for (InvoiceLine l : invoice.lines()) {
            long total = invoice.pricesIncludeVat() ? l.totalTtc() : l.totalHt();
            items.add(new Item(l.position() > 0 ? l.position() : items.size() + 1, text(l.description()),
                    text(l.reference()), InvoicePdfRenderer.quantity(l.quantity()), PdfFormats.xaf(l.unitPrice()),
                    PdfFormats.xaf(total)));
        }
        v.put("items", items);

        // totals
        InvoiceTotals t = invoice.totals();
        List<Row> totals = new ArrayList<>();
        totals.add(new Row(invoice.pricesIncludeVat() ? "Sous-total TTC" : "Sous-total", PdfFormats.xaf(t.subtotal()),
                ""));
        if (t.discount() > 0) {
            totals.add(new Row("Remise", "-" + PdfFormats.xaf(t.discount()), "discount"));
        }
        if (t.totalVat() > 0) {
            String rate = InvoicePdfRenderer.quantity(invoice.vatRate()) + PdfFormats.NBSP + "%";
            totals.add(new Row(invoice.pricesIncludeVat() ? "dont TVA (" + rate + ")" : "TVA (" + rate + ")",
                    PdfFormats.xaf(t.totalVat()), ""));
        }
        if (t.delivery() > 0) {
            totals.add(new Row("Livraison", PdfFormats.xaf(t.delivery()), ""));
        }
        v.put("totalRows", totals);
        v.put("total", PdfFormats.xaf(t.totalTtc()));
        v.put("vatMention", text(invoice.vatMention()));

        // status
        PaymentStateCalculator.Collection collection = r.collection();
        String[] status = status(invoice, collection);
        v.put("statusClass", status[0]);
        v.put("statusLabel", status[1]);

        // payment block (Bref renderPaymentSection) + the classic layout's highlighted amount
        long due = collection == null ? t.totalTtc() : collection.amountDue();
        long paid = collection == null ? (invoice.status() == InvoiceStatus.PAID ? due : 0) : collection.amountPaid();
        long remaining = Math.max(0, due - paid);
        v.put("payments", paymentRows(invoice, collection, true, paid, remaining));
        v.put("paymentsCompact", paymentRows(invoice, collection, false, paid, remaining));
        boolean showDue = !quote && invoice.status() == InvoiceStatus.ISSUED && remaining > 0;
        v.put("highlightLabel", showDue ? "Reste à payer" : "Total");
        v.put("highlightAmount", PdfFormats.xaf(showDue ? remaining : t.totalTtc()));
        v.put("paymentMethod", invoice.status() == InvoiceStatus.PAID ? text(invoice.paymentMethod()) : null);

        // signatures
        v.put("clientSignature", r.clientSignature());
        v.put("sellerSignature", r.sellerSignature());
        v.put("hasSignatures", r.clientSignature() != null || r.sellerSignature() != null);

        v.put("barcode", barcode(String.valueOf(v.get("number"))));
        return v;
    }

    /** {@code FAC-2026-000001}, {@code DV-1A2B3C4D}, {@code BROUILLON}; an imported invoice adds its Bref reference. */
    static String number(Invoice invoice, String legacyReference) {
        String own = invoice.isQuote() ? invoice.quoteReference()
                : invoice.number() == null ? null : text(invoice.number());
        if (legacyReference != null) {
            return own == null ? legacyReference : own + " (" + legacyReference + ")";
        }
        return own == null ? "BROUILLON" : own;
    }

    private static LocalDate documentDate(Invoice invoice) {
        if (!invoice.isQuote() && invoice.issueDate() != null) {
            return invoice.issueDate();
        }
        Instant created = invoice.createdAt() == null ? Instant.now() : invoice.createdAt();
        return LocalDate.ofInstant(created, KeevoZones.WAT);
    }

    /** {css class, French label} of Bref's status badge. */
    static String[] status(Invoice invoice, PaymentStateCalculator.Collection collection) {
        if (invoice.isQuote()) {
            return switch (invoice.status()) {
                case CANCELLED -> new String[]{"cancelled", "Devis annulé"};
                case CONVERTED -> new String[]{"paid", "Converti en facture"};
                default -> new String[]{"quote", "En attente de validation"};
            };
        }
        if (invoice.status() == InvoiceStatus.DRAFT) {
            return new String[]{"draft", "Brouillon"};
        }
        if (invoice.status() == InvoiceStatus.CANCELLED) {
            return new String[]{"cancelled", "Annulée"};
        }
        if (invoice.status() == InvoiceStatus.PAID) {
            return new String[]{"paid", "Payée"};
        }
        PaymentState state = collection == null ? null : collection.state();
        if (state == PaymentState.OVERDUE) {
            return new String[]{"overdue", "En retard"};
        }
        if (state == PaymentState.PARTIAL) {
            return new String[]{"partial", "Partiellement payée"};
        }
        return new String[]{"pending", "En attente"};
    }

    /**
     * Bref's payment block: cancelled → its reason; quote or invoice paid at once (no payment row) → nothing; else
     * every payment received / instalment (unless {@code installments} is false — compact ticket), « Montant payé »,
     * « Reste à payer ».
     */
    static List<Row> paymentRows(Invoice invoice, PaymentStateCalculator.Collection collection, boolean installments,
                                 long paid, long remaining) {
        List<Row> rows = new ArrayList<>();
        if (invoice.isQuote()) {
            return rows;
        }
        if (invoice.status() == InvoiceStatus.CANCELLED) {
            String reason = text(invoice.cancellationReason());
            if (reason != null) {
                rows.add(new Row("Motif d'annulation", reason, "void"));
            }
            return rows;
        }
        List<InvoicePayment> payments = collection == null ? List.of()
                : collection.payments().stream().filter(p -> !p.legacy()).toList();
        if (invoice.status() == InvoiceStatus.PAID && payments.isEmpty()) {
            return rows;
        }
        if (invoice.status() == InvoiceStatus.DRAFT && payments.isEmpty()) {
            return rows;
        }
        if (installments) {
            for (InvoicePayment p : payments) {
                rows.add(paymentRow(p));
            }
        }
        rows.add(new Row("Montant payé", PdfFormats.xaf(paid), ""));
        rows.add(new Row("Reste à payer", PdfFormats.xaf(remaining), "strong"));
        return rows;
    }

    /** « Échéance 15/12/2026 (payée) » for an instalment, « Versement du 02/11/2026 » for a payment received. */
    static Row paymentRow(InvoicePayment p) {
        String label;
        if (p.dueDate() != null) {
            label = "Échéance " + PdfFormats.date(p.dueDate()) + (p.isPaid() ? " (payée)" : "");
        } else {
            label = "Versement du " + PdfFormats.date(p.paidAt());
        }
        String method = text(p.method());
        if (method != null && p.isPaid()) {
            label = label + " — " + method;
        }
        return new Row(label, PdfFormats.xaf(p.amount()), p.isPaid() ? "paid" : "");
    }

    /**
     * The decorative bars of Bref's classic tickets (a CSS gradient there, not supported by the PDF engine): widths in
     * tenths of millimetre, alternating bar / space, derived from the printed number (same number → same bars).
     */
    static List<Integer> barcode(String number) {
        List<Integer> bars = new ArrayList<>();
        int seed = number == null ? 7 : number.hashCode();
        for (int i = 0; i < 44; i++) {
            seed = seed * 1103515245 + 12345;
            bars.add(3 + Math.floorMod(seed >>> 16, 3) * 3);
        }
        return bars;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        return PdfFormats.text(value.strip());
    }
}

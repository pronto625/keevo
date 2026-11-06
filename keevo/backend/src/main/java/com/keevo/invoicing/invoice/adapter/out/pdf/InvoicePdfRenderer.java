package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceTotals;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import com.keevo.shared.infrastructure.pdf.PdfFormats;
import org.springframework.stereotype.Component;

import com.keevo.shared.domain.time.KeevoZones;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The invoice PDF (Story 14.2, MOD-FR17): {@code pdf/templates/invoice.html} filled through the core
 * {@link PdfDocumentFactory} (A4, French, Liberation Sans). Every value is pre-formatted here (amounts grouped,
 * XAF, dates dd/MM/yyyy) and written by the template with escaped {@code th:text} only; no identifier other than the
 * invoice number is ever printed. Story 20.3: a quote prints « DEVIS DV-XXXXXXXX », its creation date, no legal
 * number, and the mention « Devis — en attente de validation » while it waits for its conversion.
 */
@Component
public class InvoicePdfRenderer implements InvoiceDocumentRenderer {

    static final String TEMPLATE = "invoice";

    private final PdfDocumentFactory documents;

    public InvoicePdfRenderer(PdfDocumentFactory documents) {
        this.documents = documents;
    }

    /** One printed line: every cell already formatted. */
    public record LineView(String description, String quantity, String unitPrice, String vat, String totalHt,
                           String totalVat, String totalTtc, String reference) {

        /** Pre-20.5 shape: no reference. */
        public LineView(String description, String quantity, String unitPrice, String vat, String totalHt,
                        String totalVat, String totalTtc) {
            this(description, quantity, unitPrice, vat, totalHt, totalVat, totalTtc, null);
        }
    }

    @Override
    public byte[] render(Invoice invoice, SellerSnapshot seller, Logo logo) {
        return documents.render(TEMPLATE, variables(invoice, seller, logo));
    }

    static Map<String, Object> variables(Invoice invoice, SellerSnapshot seller, Logo logo) {
        Map<String, Object> v = new HashMap<>();
        v.put("logo", logo == null ? null : logo.dataUri());
        v.put("sellerName", text(seller == null ? null : seller.legalName()));
        v.put("sellerAddress", text(seller == null ? null : seller.address()));
        v.put("sellerPhone", text(seller == null ? null : seller.phone()));
        v.put("sellerNiu", text(seller == null ? null : seller.niu()));
        v.put("sellerRccm", text(seller == null ? null : seller.rccm()));
        v.put("paymentDetails", text(seller == null ? null : seller.paymentDetails()));
        v.put("footer", text(seller == null ? null : seller.footer()));

        boolean draft = invoice.status() == InvoiceStatus.DRAFT;
        if (invoice.isQuote()) {
            // Story 20.3 — a quote: its display reference, never a legal number nor an issue date
            v.put("title", "DEVIS " + invoice.quoteReference());
            v.put("issueDate", PdfFormats.date(LocalDate.ofInstant(invoice.createdAt(), KeevoZones.WAT)));
        } else {
            v.put("title", draft ? "FACTURE — BROUILLON" : "FACTURE N° " + text(invoice.number()));
            v.put("issueDate", draft ? "Non émise" : PdfFormats.date(invoice.issueDate()));
        }

        ClientInfo c = invoice.client();
        v.put("clientName", text(c.name()));
        v.put("clientNiu", text(c.niu()));
        v.put("clientAddress", text(c.address()));
        v.put("clientPhone", text(c.phone()));
        // Story 20.5 — client data copied on the document, when present
        v.put("clientRccm", text(c.rccm()));
        v.put("clientEmail", text(c.email()));
        v.put("clientContact", text(c.contactName()));
        v.put("clientWebsite", text(c.website()));
        v.put("dueDate", invoice.terms().dueDate() == null ? null : PdfFormats.date(invoice.terms().dueDate()));

        v.put("unitPriceLabel", invoice.pricesIncludeVat() ? "Prix unitaire TTC" : "Prix unitaire HT");
        List<LineView> lines = new ArrayList<>();
        for (InvoiceLine l : invoice.lines()) {
            lines.add(new LineView(text(l.description()), quantity(l.quantity()), PdfFormats.integer(l.unitPrice()),
                    vat(l), PdfFormats.integer(l.totalHt()), PdfFormats.integer(l.totalVat()),
                    PdfFormats.integer(l.totalTtc()), text(l.reference())));
        }
        v.put("lines", lines);
        // Story 20.5 — with prices without VAT the VAT is computed once on the document: the lines show HT and rate
        // only (per-line VAT / TTC could not add up to the document's totals)
        v.put("lineTaxColumns", invoice.pricesIncludeVat());
        InvoiceTotals t = invoice.totals();
        // Story 20.5 — Sous-total, Remise, Livraison only on a document with a discount, a delivery fee or an
        // exemption: any other invoice prints exactly as before (Total HT, Total TVA, Total TTC)
        boolean breakdown = invoice.terms().hasAdjustments() || invoice.terms().vatExempt();
        v.put("subtotalLabel", invoice.pricesIncludeVat() ? "Sous-total TTC" : "Sous-total HT");
        v.put("subtotal", breakdown ? PdfFormats.xaf(t.subtotal()) : null);
        v.put("discount", t.discount() > 0 ? "-" + PdfFormats.xaf(t.discount()) : null);
        v.put("deliveryFee", t.delivery() > 0 ? PdfFormats.xaf(t.delivery()) : null);
        v.put("totalHt", PdfFormats.xaf(t.totalHt()));
        v.put("totalVat", PdfFormats.xaf(t.totalVat()));
        v.put("totalTtc", PdfFormats.xaf(t.totalTtc()));
        v.put("vatMention", text(invoice.vatMention()));

        v.put("stamp", stamp(invoice));
        String reason = text(invoice.cancellationReason());
        v.put("stampDetail", invoice.status() == InvoiceStatus.CANCELLED && reason != null
                ? "Motif : " + reason : null);
        v.put("paymentMethod", invoice.status() == InvoiceStatus.PAID && invoice.paymentMethod() != null
                ? "Mode de paiement : " + text(invoice.paymentMethod()) : null);
        v.put("stampClass", invoice.isQuote() && invoice.status() == InvoiceStatus.DRAFT ? "stamp"
                : switch (invoice.status()) {
                    case PAID -> "stamp paid";
                    case CANCELLED, DRAFT -> "stamp void";
                    case ISSUED, CONVERTED -> "stamp";
                });
        return v;
    }

    /** Story 20.3 — the mention printed on a quote waiting for its conversion. */
    static final String QUOTE_PENDING = "Devis — en attente de validation";

    static String stamp(Invoice invoice) {
        if (invoice.isQuote()) {
            return switch (invoice.status()) {
                case CANCELLED -> "DEVIS ANNULÉ";
                case CONVERTED -> "DEVIS CONVERTI EN FACTURE";
                default -> QUOTE_PENDING;
            };
        }
        return switch (invoice.status()) {
            case DRAFT -> "BROUILLON — sans valeur";
            case CANCELLED -> "ANNULÉE";
            case PAID -> "PAYÉE le " + PdfFormats.date(invoice.paidAt());
            case ISSUED, CONVERTED -> null;
        };
    }

    /** {@code 1.000} → {@code 1}; {@code 2.500} → {@code 2,5}. */
    static String quantity(BigDecimal q) {
        if (q == null) return "";
        BigDecimal stripped = q.stripTrailingZeros();
        if (stripped.scale() < 0) stripped = stripped.setScale(0);
        return stripped.toPlainString().replace('.', ',');
    }

    /** The per-line mention (« Exonérée », « Prise en charge État ») or the rate applied ({@code 19,25 %}). */
    static String vat(InvoiceLine l) {
        if (l.vatMention() != null) return l.vatMention();
        return quantity(l.vatRate()) + PdfFormats.NBSP + "%";
    }

    /** Blank → null (the template hides the row); XML-illegal characters dropped. */
    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        return PdfFormats.text(value.strip());
    }
}

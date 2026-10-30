package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import com.keevo.shared.infrastructure.pdf.PdfFormats;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The invoice PDF (Story 14.2, MOD-FR17): {@code pdf/templates/invoice.html} filled through the core
 * {@link PdfDocumentFactory} (A4, French, Liberation Sans). Every value is pre-formatted here (amounts grouped,
 * XAF, dates dd/MM/yyyy) and written by the template with escaped {@code th:text} only; no identifier other than the
 * invoice number is ever printed.
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
                           String totalVat, String totalTtc) {
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
        v.put("title", draft ? "FACTURE — BROUILLON" : "FACTURE N° " + text(invoice.number()));
        v.put("issueDate", draft ? "Non émise" : PdfFormats.date(invoice.issueDate()));

        ClientInfo c = invoice.client();
        v.put("clientName", text(c.name()));
        v.put("clientNiu", text(c.niu()));
        v.put("clientAddress", text(c.address()));
        v.put("clientPhone", text(c.phone()));

        v.put("unitPriceLabel", invoice.pricesIncludeVat() ? "Prix unitaire TTC" : "Prix unitaire HT");
        List<LineView> lines = new ArrayList<>();
        for (InvoiceLine l : invoice.lines()) {
            lines.add(new LineView(text(l.description()), quantity(l.quantity()), PdfFormats.integer(l.unitPrice()),
                    vat(l), PdfFormats.integer(l.totalHt()), PdfFormats.integer(l.totalVat()),
                    PdfFormats.integer(l.totalTtc())));
        }
        v.put("lines", lines);
        v.put("totalHt", PdfFormats.xaf(invoice.totals().totalHt()));
        v.put("totalVat", PdfFormats.xaf(invoice.totals().totalVat()));
        v.put("totalTtc", PdfFormats.xaf(invoice.totals().totalTtc()));
        v.put("vatMention", text(invoice.vatMention()));

        v.put("stamp", stamp(invoice));
        String reason = text(invoice.cancellationReason());
        v.put("stampDetail", invoice.status() == InvoiceStatus.CANCELLED && reason != null
                ? "Motif : " + reason : null);
        v.put("paymentMethod", invoice.status() == InvoiceStatus.PAID && invoice.paymentMethod() != null
                ? "Mode de paiement : " + text(invoice.paymentMethod()) : null);
        v.put("stampClass", switch (invoice.status()) {
            case PAID -> "stamp paid";
            case CANCELLED, DRAFT -> "stamp void";
            case ISSUED -> "stamp";
        });
        return v;
    }

    static String stamp(Invoice invoice) {
        return switch (invoice.status()) {
            case DRAFT -> "BROUILLON — sans valeur";
            case CANCELLED -> "ANNULÉE";
            case PAID -> "PAYÉE le " + PdfFormats.date(invoice.paidAt());
            case ISSUED -> null;
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

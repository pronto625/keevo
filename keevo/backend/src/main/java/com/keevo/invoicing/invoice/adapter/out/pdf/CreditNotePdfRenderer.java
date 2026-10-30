package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteDocumentRenderer;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import com.keevo.shared.infrastructure.pdf.PdfFormats;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The credit note PDF — « avoir » (Story 14.7): {@code pdf/templates/credit-note.html}, the invoice layout with the
 * title « AVOIR N° AV-… », the reference « Avoir sur la facture FAC-… du … », the reason, and the credited lines and
 * totals printed with a leading « − ». Seller identity = the snapshot of the credited invoice; client = the invoice's.
 * Every value pre-formatted here and written with escaped {@code th:text}; no identifier other than the two numbers.
 */
@Component
public class CreditNotePdfRenderer implements CreditNoteDocumentRenderer {

    static final String TEMPLATE = "credit-note";

    /** U+2212 MINUS SIGN. */
    static final String MINUS = "−";

    private final PdfDocumentFactory documents;

    public CreditNotePdfRenderer(PdfDocumentFactory documents) {
        this.documents = documents;
    }

    @Override
    public byte[] render(CreditNote creditNote, Invoice invoice, Logo logo) {
        return documents.render(TEMPLATE, variables(creditNote, invoice, logo));
    }

    static Map<String, Object> variables(CreditNote note, Invoice invoice, Logo logo) {
        Map<String, Object> v = new HashMap<>();
        SellerSnapshot seller = invoice.seller();
        v.put("logo", logo == null ? null : logo.dataUri());
        v.put("sellerName", text(seller == null ? null : seller.legalName()));
        v.put("sellerAddress", text(seller == null ? null : seller.address()));
        v.put("sellerPhone", text(seller == null ? null : seller.phone()));
        v.put("sellerNiu", text(seller == null ? null : seller.niu()));
        v.put("sellerRccm", text(seller == null ? null : seller.rccm()));
        v.put("footer", text(seller == null ? null : seller.footer()));

        v.put("title", "AVOIR N° " + text(note.number()));
        v.put("issueDate", PdfFormats.date(note.issuedAt()));
        v.put("reference", "Avoir sur la facture " + text(invoice.number()) + " du "
                + PdfFormats.date(invoice.issueDate()));
        v.put("reason", text(note.reason()));

        ClientInfo c = invoice.client();
        v.put("clientName", text(c.name()));
        v.put("clientNiu", text(c.niu()));
        v.put("clientAddress", text(c.address()));
        v.put("clientPhone", text(c.phone()));

        v.put("unitPriceLabel", invoice.pricesIncludeVat() ? "Prix unitaire TTC" : "Prix unitaire HT");
        List<InvoicePdfRenderer.LineView> lines = new ArrayList<>();
        for (CreditNoteLine l : note.lines()) {
            lines.add(new InvoicePdfRenderer.LineView(text(l.description()), InvoicePdfRenderer.quantity(l.quantity()),
                    PdfFormats.integer(l.unitPrice()), vat(l), negative(PdfFormats.integer(l.totalHt()), l.totalHt()),
                    negative(PdfFormats.integer(l.totalVat()), l.totalVat()),
                    negative(PdfFormats.integer(l.totalTtc()), l.totalTtc())));
        }
        v.put("lines", lines);
        v.put("totalHt", negative(PdfFormats.xaf(note.totals().totalHt()), note.totals().totalHt()));
        v.put("totalVat", negative(PdfFormats.xaf(note.totals().totalVat()), note.totals().totalVat()));
        v.put("totalTtc", negative(PdfFormats.xaf(note.totals().totalTtc()), note.totals().totalTtc()));
        v.put("vatMention", text(invoice.vatMention()));
        return v;
    }

    /** {@code −2 000} for a credited amount; {@code 0} stays unsigned. */
    static String negative(String formatted, long amount) {
        return amount == 0 ? formatted : MINUS + formatted;
    }

    /** The per-line mention (« Exonérée », « Prise en charge État ») or the rate applied ({@code 19,25 %}). */
    static String vat(CreditNoteLine l) {
        if (l.vatMention() != null) return l.vatMention();
        return InvoicePdfRenderer.quantity(l.vatRate()) + PdfFormats.NBSP + "%";
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        return PdfFormats.text(value.strip());
    }
}

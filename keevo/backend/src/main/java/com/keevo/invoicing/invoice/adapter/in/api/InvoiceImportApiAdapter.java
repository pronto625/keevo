package com.keevo.invoicing.invoice.adapter.in.api;

import com.keevo.invoicing.api.InvoiceImportPort;
import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceImportWriter;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceImportWriter.CollectionInput;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.4 — implements {@link InvoiceImportPort}: every value is checked against the rules a stored document must
 * meet to be read back (the table constraints of {@code db/modules/invoicing}, the field limits, the quote and status
 * invariants) before any write; then one insert per table, the collection states computed by
 * {@link PaymentStateCalculator} (the module's single rule) and written in one statement, the old Bref links recorded
 * in one statement. Joins the caller's transaction; the tenant context must be the explicit schema. Never issues,
 * numbers, counts a quota nor publishes an event.
 */
@Component
class InvoiceImportApiAdapter implements InvoiceImportPort {

    private static final Set<String> STATUSES = Set.of("DRAFT", "ISSUED", "PAID", "CANCELLED");
    private static final BigDecimal MAX_RATE = new BigDecimal("100");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");

    private final InvoiceImportWriter writer;
    private final InvoicingSettingsRepository settings;
    private final LegacyInvoiceLinkRegistry legacyLinks;

    InvoiceImportApiAdapter(InvoiceImportWriter writer, InvoicingSettingsRepository settings,
                            LegacyInvoiceLinkRegistry legacyLinks) {
        this.writer = writer;
        this.settings = settings;
        this.legacyLinks = legacyLinks;
    }

    @Override
    public void importSettings(String tenantSchema, Seller seller, Instant at) {
        TenantContext.requireCurrent(tenantSchema);
        check(seller);
        BigDecimal rate = settings.countryVatRate();
        writer.insertSettingsIfAbsent(new InvoicingSettings(seller.legalName(), seller.address(), seller.phone(),
                seller.niu(), seller.rccm(), false, rate == null ? InvoicingSettings.DEFAULT_VAT_RATE : rate, false,
                null, null), at);
        writer.insertPreferencesIfAbsent(DocumentPreferences.IMPORTED_LINK_DAYS, at);
    }

    @Override
    public Result importDocuments(String tenantSchema, List<Document> documents, List<Line> lines,
                                  List<Payment> payments, LocalDate today) {
        TenantContext.requireCurrent(tenantSchema);
        List<Document> docs = documents == null ? List.of() : documents;
        List<Line> rows = lines == null ? List.of() : lines;
        List<Payment> pays = payments == null ? List.of() : payments;
        Set<UUID> tokens = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (Document d : docs) {
            check(d);
            require(ids.add(d.id()), "duplicate document");
            require(d.number() == null || numbers.add(d.number()), "duplicate number");
            require(d.legacyToken() == null || tokens.add(d.legacyToken()), "duplicate public token");
            require(d.legacyToken() == null || d.deletedAt() == null, "public token on a deleted document");
        }
        Set<UUID> lineIds = new HashSet<>();
        Set<String> positions = new HashSet<>();
        rows.forEach(l -> {
            check(l);
            require(lineIds.add(l.id()), "duplicate line");
            require(positions.add(l.documentId() + ":" + l.position()), "duplicate line position");
        });
        Set<UUID> paymentIds = new HashSet<>();
        pays.forEach(p -> {
            check(p);
            require(paymentIds.add(p.id()), "duplicate payment");
        });
        Set<UUID> insertedDocuments = writer.insertDocuments(docs);
        Set<UUID> insertedLines = writer.insertLines(rows);
        Set<UUID> insertedPayments = writer.insertPayments(pays);

        Set<UUID> concerned = new LinkedHashSet<>();
        docs.forEach(d -> concerned.add(d.id()));
        pays.forEach(p -> concerned.add(p.documentId()));
        Map<UUID, PaymentState> states = new HashMap<>();
        for (CollectionInput c : writer.collections(concerned)) {
            states.put(c.id(), state(c, today));
        }
        writer.updateStates(states);

        List<LegacyInvoiceLinkRegistry.Link> links = new ArrayList<>();
        for (Document d : docs) {
            if (d.legacyToken() != null && d.deletedAt() == null) {
                links.add(new LegacyInvoiceLinkRegistry.Link(d.legacyToken(), d.id(), d.legacyTokenExpiresAt()));
            }
        }
        legacyLinks.registerAll(tenantSchema, links);
        return new Result(insertedDocuments, insertedLines, insertedPayments);
    }

    /**
     * The collection state of an imported document: none for a quote or a document neither issued nor paid; PAID for
     * a paid one (Bref's legal status is kept, whatever its rows); else {@link PaymentStateCalculator#state} with the
     * invoice's own due date — OVERDUE included (the import is not a payment change: the daily job then finds it
     * already marked and notifies nothing).
     */
    static PaymentState state(CollectionInput c, LocalDate today) {
        if (c.quote() || !("ISSUED".equals(c.status()) || "PAID".equals(c.status()))) {
            return null;
        }
        if ("PAID".equals(c.status())) {
            return PaymentState.PAID;
        }
        return PaymentStateCalculator.state(PaymentStateCalculator.amountDue(c.totalTtc(), 0), c.rows(), today,
                c.dueDate());
    }

    static void check(Seller s) {
        require(s != null, "seller");
        require(fits(s.legalName(), 200) && fits(s.address(), 300) && fits(s.phone(), 30) && fits(s.niu(), 30)
                && fits(s.rccm(), 60), "seller field too long");
    }

    static void check(Document d) {
        require(STATUSES.contains(d.status()), "document status");
        if (d.quote()) {
            require(!"ISSUED".equals(d.status()) && !"PAID".equals(d.status()), "quote status");
            require(d.number() == null && d.issueDate() == null && d.issuedAt() == null, "quote numbered");
        } else {
            require(!"DRAFT".equals(d.status()), "invoice status");
            require(d.number() != null && fits(d.number(), 20) && !d.number().isBlank(), "invoice number");
            require(d.issueDate() != null && d.issuedAt() != null, "invoice issue date");
        }
        require(!"PAID".equals(d.status()) || d.paidAt() != null, "paid date");
        if ("CANCELLED".equals(d.status())) {
            require(d.cancelledAt() != null && d.cancellationReason() != null
                    && !d.cancellationReason().isBlank() && fits(d.cancellationReason(), 500), "cancellation");
        }
        require(d.subtotal() >= 0 && d.discount() >= 0 && d.deliveryFee() >= 0 && d.totalHt() >= 0
                && d.totalVat() >= 0 && d.totalTtc() >= 0, "negative amount");
        require(d.discount() <= d.subtotal(), "discount above subtotal");
        require(rate(d.vatRate()), "vat rate");
        require(!d.clientName().isBlank() && fits(d.clientName(), 200), "client name");
        require(fits(d.clientPhone(), 30) && fits(d.clientEmail(), 255) && fits(d.clientNiu(), 30)
                && fits(d.clientRccm(), 50) && fits(d.clientAddress(), 300) && fits(d.clientContactName(), 120)
                && fits(d.clientWebsite(), 200), "client field too long");
        if (d.seller() != null) {
            check(d.seller());
        }
        require(fits(d.paymentMethod(), 60), "payment method");
        require(d.legacyReference() == null
                || InvoicePresentation.LEGACY_REFERENCE.matcher(d.legacyReference()).matches(), "legacy reference");
    }

    static void check(Line l) {
        require(l.position() >= 1, "line position");
        require(!l.description().isBlank() && fits(l.description(), 500), "line description");
        require(l.quantity().signum() > 0 && l.quantity().stripTrailingZeros().scale() <= 3
                && l.quantity().compareTo(MAX_QUANTITY) <= 0, "line quantity");
        require(l.unitPrice() >= 0 && l.totalHt() >= 0 && l.totalVat() >= 0 && l.totalTtc() >= 0, "line amount");
        require(rate(l.vatRate()), "line vat rate");
        require(fits(l.reference(), 100), "line reference");
    }

    static void check(Payment p) {
        require(p.amount() > 0, "payment amount");
        require(p.dueDate() != null || p.paidAt() != null, "payment date");
        require(fits(p.method(), 60) && fits(p.note(), 500), "payment field too long");
    }

    private static boolean rate(BigDecimal r) {
        return r.signum() >= 0 && r.compareTo(MAX_RATE) <= 0 && r.stripTrailingZeros().scale() <= 2;
    }

    private static boolean fits(String value, int max) {
        return value == null || value.codePointCount(0, value.length()) <= max;
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid imported document: " + what);
        }
    }
}

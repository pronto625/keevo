package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer.RenderRequest;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PDF of an invoice, rendered on demand (Story 14.2): nothing is stored — an issued invoice is immutable, so its
 * PDF is the same every time (only the logo is the current one). Seller identity: the snapshot of an issued invoice;
 * for a draft, the current settings (legal name, else the company display name). Logo: the current company logo,
 * whatever the status (not a legal mention; the snapshot's {@code hasLogo} flag is informative only).
 *
 * <p>Story 20.7 — the layout printed is the one asked for the download ({@code template} / {@code ticketWidth}
 * overrides, a preview), else the document's, else the tenant's default, else the Keevo standard layout; the PDF
 * carries the payment block (amount paid, still due, payments and instalments — Story 20.4 collection), the client
 * and seller signatures, and the Bref reference of an imported invoice. Cost per PDF: the invoice, its presentation
 * row, the tenant preferences, its credit notes and its payments — each one query (no per-line or per-payment query);
 * at most two signature files read.
 */
@Service
public class InvoiceDocumentUseCase {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoiceDocumentUseCase.class);

    /** A rendered PDF with its download file name ({@code FAC-2026-000001.pdf}, {@code brouillon.pdf}). */
    public record InvoiceDocument(byte[] content, String filename) {
    }

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final InvoiceDocumentRenderer renderer;
    private final CompanyIdentity companyIdentity;
    private final InvoiceLinkSigner signer;
    private final InvoicePresentationRepository presentations;
    private final InvoiceQueryUseCase queries;
    private final InvoiceSignatureStorage signatures;
    private final Clock clock;

    public InvoiceDocumentUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                  InvoiceDocumentRenderer renderer, CompanyIdentity companyIdentity,
                                  InvoiceLinkSigner signer, InvoicePresentationRepository presentations,
                                  InvoiceQueryUseCase queries, InvoiceSignatureStorage signatures, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.renderer = renderer;
        this.companyIdentity = companyIdentity;
        this.signer = signer;
        this.presentations = presentations;
        this.queries = queries;
        this.signatures = signatures;
        this.clock = clock;
    }

    /** PDF of an invoice of the current tenant, in its own layout. @throws DomainException INVOICE_NOT_FOUND */
    @Transactional(readOnly = true)
    public InvoiceDocument pdf(UUID id, String tenantSchema) {
        return pdf(id, tenantSchema, null, null);
    }

    /**
     * Story 20.7 — PDF in the layout {@code template} / paper {@code width} when given (download preview), else the
     * document's / tenant's choice.
     */
    @Transactional(readOnly = true)
    public InvoiceDocument pdf(UUID id, String tenantSchema, InvoiceTemplate template, TicketWidth width) {
        Invoice invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
        InvoicePresentation presentation = presentations.find(id).orElse(InvoicePresentation.NONE);
        DocumentPreferences preferences = presentations.preferences();
        InvoiceTemplate layout = template != null ? template : presentation.effectiveTemplate(preferences);
        TicketWidth paper = width != null ? width : presentation.effectiveWidth(preferences);
        RenderRequest request = new RenderRequest(invoice, seller(invoice, tenantSchema), currentLogo(tenantSchema),
                layout, paper, collection(invoice),
                signature(tenantSchema, presentation, SignatureRole.CLIENT),
                signature(tenantSchema, presentation, SignatureRole.SELLER), presentation.legacyReference());
        return new InvoiceDocument(renderer.render(request), filename(invoice, presentation.legacyReference()));
    }

    /**
     * Resolves a public link (Story 14.2): signature, expiry, tenant still ACTIVE. Tampered, unknown or suspended →
     * {@code INVOICE_LINK_INVALID} (404, no detail); expired → {@code INVOICE_LINK_EXPIRED} (410). The link version
     * (Story 20.7 rotation) is checked once the tenant is bound ({@link InvoicePublicLinkUseCase#assertCurrentVersion}).
     */
    public InvoiceLinkSigner.Claims verifyLink(String token) {
        InvoiceLinkSigner.Claims claims = signer.verify(token);
        if (!claims.expiresAt().isAfter(clock.instant())) {
            throw new DomainException(ErrorCode.INVOICE_LINK_EXPIRED, "Invoice link expired");
        }
        if (!companyIdentity.isActive(claims.tenantSchema())) {
            throw new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Invoice link of an inactive tenant");
        }
        return claims;
    }

    /** Story 20.4 / 20.7 — the document's collection (payments, amounts); {@code null} for a quote. */
    private PaymentStateCalculator.Collection collection(Invoice invoice) {
        if (invoice.isQuote()) {
            return null;
        }
        Map<UUID, InvoiceQueryUseCase.Credits> credits = invoice.status() == InvoiceStatus.DRAFT ? Map.of()
                : queries.credits(List.of(invoice));
        return queries.collections(List.of(invoice), credits).get(invoice.id());
    }

    /** The signature image of {@code role} as a data URI; a missing / unreadable file → unsigned on the PDF. */
    private String signature(String tenantSchema, InvoicePresentation presentation, SignatureRole role) {
        String file = presentation.signature(role);
        if (file == null) {
            return null;
        }
        try {
            return signatures.read(tenantSchema, file).map(InvoiceSignatureStorage.StoredImage::dataUri).orElse(null);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("INVOICE_PDF_SIGNATURE_UNREADABLE error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The current company logo, whatever the invoice status (a logo is not a legal mention); a read error is logged
     * and the PDF is rendered without logo rather than failing.
     */
    private Logo currentLogo(String tenantSchema) {
        try {
            return companyIdentity.logo(tenantSchema).orElse(null);
        } catch (RuntimeException e) {
            log.warn("INVOICE_PDF_LOGO_UNREADABLE error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private SellerSnapshot seller(Invoice invoice, String tenantSchema) {
        if (invoice.seller() != null) {
            return invoice.seller();
        }
        InvoicingSettings current = settings.find()
                .orElseGet(() -> InvoicingSettings.defaults(settings.countryVatRate()));
        boolean fromDisplay = current.legalName() == null;
        InvoicingSettings printed = fromDisplay
                ? InvoiceLifecycleUseCase.withLegalName(current, companyIdentity.displayName(tenantSchema)) : current;
        return SellerSnapshot.of(printed).withIdentityFlags(fromDisplay, false);
    }

    static String filename(Invoice invoice) {
        return filename(invoice, null);
    }

    /** Story 20.7 — an imported invoice without a Keevo number downloads as {@code BRF-XXXXXXXX.pdf}. */
    static String filename(Invoice invoice, String legacyReference) {
        if (invoice.isQuote()) { // Story 20.3
            return "devis-".concat(invoice.quoteReference()).concat(".pdf");
        }
        if (invoice.number() == null && legacyReference != null && invoice.status() != InvoiceStatus.DRAFT) {
            return legacyReference.concat(".pdf");
        }
        return invoice.status() == InvoiceStatus.DRAFT || invoice.number() == null
                ? "brouillon.pdf" : invoice.number().concat(".pdf");
    }
}

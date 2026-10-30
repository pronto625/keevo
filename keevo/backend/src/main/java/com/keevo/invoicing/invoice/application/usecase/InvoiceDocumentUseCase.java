package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The PDF of an invoice, rendered on demand (Story 14.2): nothing is stored — an issued invoice is immutable, so its
 * PDF is the same every time (only the logo is the current one). Seller identity: the snapshot of an issued invoice;
 * for a draft, the current settings (legal name, else the company display name). Logo: the current company logo,
 * whatever the status (not a legal mention; the snapshot's {@code hasLogo} flag is informative only).
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
    private final Clock clock;

    public InvoiceDocumentUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                  InvoiceDocumentRenderer renderer, CompanyIdentity companyIdentity,
                                  InvoiceLinkSigner signer, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.renderer = renderer;
        this.companyIdentity = companyIdentity;
        this.signer = signer;
        this.clock = clock;
    }

    /** PDF of an invoice of the current tenant. @throws DomainException INVOICE_NOT_FOUND */
    @Transactional(readOnly = true)
    public InvoiceDocument pdf(UUID id, String tenantSchema) {
        Invoice invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
        SellerSnapshot seller = seller(invoice, tenantSchema);
        return new InvoiceDocument(renderer.render(invoice, seller, currentLogo(tenantSchema)), filename(invoice));
    }

    /**
     * Resolves a public link (Story 14.2): signature, expiry, tenant still ACTIVE. Tampered, unknown or suspended →
     * {@code INVOICE_LINK_INVALID} (404, no detail); expired → {@code INVOICE_LINK_EXPIRED} (410).
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
        InvoicingSettings current = settings.find().orElseGet(InvoicingSettings::defaults);
        boolean fromDisplay = current.legalName() == null;
        InvoicingSettings printed = fromDisplay
                ? InvoiceLifecycleUseCase.withLegalName(current, companyIdentity.displayName(tenantSchema)) : current;
        return SellerSnapshot.of(printed).withIdentityFlags(fromDisplay, false);
    }

    static String filename(Invoice invoice) {
        return invoice.status() == InvoiceStatus.DRAFT || invoice.number() == null
                ? "brouillon.pdf" : invoice.number().concat(".pdf");
    }
}

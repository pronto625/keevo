package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkRateLimiter;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleProvisioner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR23) — an old Bref public link, redirected to Keevo:
 * {@code GET /api/v1/invoices/public/{token}} (Bref's path {@code /v1/invoices/public/:token}, token = Bref's UUID) →
 * the PDF of the imported invoice, Bref reference printed next to the number. No JWT
 * ({@code SecurityConfig.PUBLIC_PATHS}). Unknown / malformed token, tenant not ACTIVE or invoice gone → 404
 * {@code INVOICE_LINK_INVALID}; past Bref's expiry → 410 {@code INVOICE_LINK_EXPIRED}; 30 openings per token per 10
 * minutes (429); anything unexpected → French 503. Same tenant binding as {@link InvoiceSharedLinkController}.
 */
@EntitlementExempt("ancien lien public Bref d'une facture importée (lien déjà partagé au client)")
@RestController
public class InvoiceLegacyLinkController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoiceLegacyLinkController.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final InvoicePublicLinkUseCase links;
    private final InvoiceDocumentUseCase documents;
    private final ObjectProvider<ModuleProvisioner> provisioner;
    private final InvoiceLinkRateLimiter limiter;

    public InvoiceLegacyLinkController(InvoicePublicLinkUseCase links, InvoiceDocumentUseCase documents,
                                       ObjectProvider<ModuleProvisioner> provisioner,
                                       InvoiceLinkRateLimiter limiter) {
        this.links = links;
        this.documents = documents;
        this.provisioner = provisioner;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/invoices/public/{token}")
    public ResponseEntity<byte[]> legacy(@PathVariable("token") String token) {
        if (token == null || !UUID_TEXT.matcher(token).matches()) {
            throw new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Malformed legacy invoice link");
        }
        limiter.acquire("legacy:" + token.toLowerCase(java.util.Locale.ROOT));
        LegacyInvoiceLinkRegistry.Target target;
        try {
            target = links.resolveLegacy(UUID.fromString(token));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        String schema = TenantSchema.validate(target.tenantSchema());
        TenantContext.setCurrentTenant(schema);
        try {
            ModuleProvisioner p = provisioner.getIfAvailable();
            if (p != null) {
                p.ensureProvisioned(schema, InvoicingModule.ID);
            }
            return InvoiceController.pdfResponse(documents.pdf(target.invoiceId(), schema));
        } catch (DomainException e) {
            if ("INVOICE_NOT_FOUND".equals(e.getDomainCode()) || "SERVICE_UNAVAILABLE".equals(e.getDomainCode())) {
                throw new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Legacy invoice link target not available");
            }
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        } finally {
            TenantContext.clear();
        }
    }

    private static DomainException unavailable(RuntimeException e) {
        log.warn("INVOICE_LEGACY_LINK_FAILED error={}", e.getClass().getSimpleName());
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Legacy invoice link could not be served");
    }
}

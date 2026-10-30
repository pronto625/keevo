package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkRateLimiter;
import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public signed link to an invoice PDF (Story 14.2): {@code GET /api/v1/invoices/shared?token=…}, opened by the
 * client from the WhatsApp message — no JWT ({@code SecurityConfig.PUBLIC_PATHS}).
 *
 * <p>The HMAC token binds tenant schema, invoice and expiry (30 days). Tampered token, unknown invoice or tenant not
 * ACTIVE → 404 {@code INVOICE_LINK_INVALID}, expired → 410 {@code INVOICE_LINK_EXPIRED}, no detail leaked. The
 * controller validates the schema and sets / clears {@link TenantContext} itself; the module's tables are brought to
 * their latest version first (lazy provisioning, the gated endpoints are bypassed). Each token opens at most 30
 * times per 10 minutes (429); an unexpected failure answers a French 503.
 */
@EntitlementExempt("lien signé envoyé au client (facture déjà contrôlée à l'envoi)")
@RestController
public class InvoiceSharedLinkController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoiceSharedLinkController.class);

    private final InvoiceDocumentUseCase documents;
    private final ObjectProvider<ModuleProvisioner> provisioner;
    private final InvoiceLinkRateLimiter limiter;

    public InvoiceSharedLinkController(InvoiceDocumentUseCase documents,
                                       ObjectProvider<ModuleProvisioner> provisioner,
                                       InvoiceLinkRateLimiter limiter) {
        this.documents = documents;
        this.provisioner = provisioner;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/invoices/shared")
    public ResponseEntity<byte[]> shared(@RequestParam("token") String token) {
        limiter.acquire(token == null ? "" : token);
        InvoiceLinkSigner.Claims claims;
        try {
            claims = documents.verifyLink(token);
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        String schema = TenantSchema.validate(claims.tenantSchema());
        TenantContext.setCurrentTenant(schema);
        try {
            ModuleProvisioner p = provisioner.getIfAvailable();
            if (p != null) {
                p.ensureProvisioned(schema, InvoicingModule.ID);
            }
            return InvoiceController.pdfResponse(documents.pdf(claims.invoiceId(), schema));
        } catch (DomainException e) {
            if ("INVOICE_NOT_FOUND".equals(e.getDomainCode()) || "SERVICE_UNAVAILABLE".equals(e.getDomainCode())) {
                throw new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Invoice link target not available");
            }
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        } finally {
            TenantContext.clear();
        }
    }

    /** Anything unexpected on this public endpoint → French 503, never a generic 500. */
    private static DomainException unavailable(RuntimeException e) {
        log.warn("INVOICE_LINK_FAILED error={}", e.getClass().getSimpleName());
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Invoice link could not be served");
    }
}

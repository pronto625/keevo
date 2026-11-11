package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkRateLimiter;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.infrastructure.web.PublicBaseUrl;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleProvisioner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 20.7 / 24.10 (fusion Keevo × Bref, FUS-FR23, FUS-MIG9) — an old Bref public link, after the cutover:
 * {@code GET /api/v1/invoices/public/{token}} (Bref's own path {@code /api/v1/invoices/public/:token}, token = Bref's
 * UUID; the Bref domain redirects here, runbook {@code docs/RUNBOOK_BASCULE_BREF.md}) → <b>302</b> to the signed Keevo
 * link of the imported document ({@code /api/v1/invoices/shared?token=…}, which serves the PDF with the Bref reference
 * next to the number). No JWT ({@code SecurityConfig.PUBLIC_PATHS}). Unknown / malformed token, tenant not ACTIVE,
 * invoice gone or no longer holding the token → 404 {@code INVOICE_LINK_INVALID}; past Bref's expiry → 410
 * {@code INVOICE_LINK_EXPIRED}; a browser gets a French page instead of JSON ({@link PublicLinkErrorPages}). Rate
 * limits: 30 openings per resolving token per 10 minutes, and 20 misses (unknown / expired / malformed token) per
 * client address (behind the proxy: {@link ClientIps}) per 10 minutes — 429; anything unexpected → French 503. The
 * redirect is never cached ({@code no-store}) and sends no referrer. Same tenant binding as
 * {@link InvoiceSharedLinkController}.
 */
@EntitlementExempt("ancien lien public Bref d'une facture importée (lien déjà partagé au client)")
@RestController
public class InvoiceLegacyLinkController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoiceLegacyLinkController.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final InvoicePublicLinkUseCase links;
    private final ObjectProvider<ModuleProvisioner> provisioner;
    private final InvoiceLinkRateLimiter limiter;
    private final PublicBaseUrl publicBaseUrl;

    public InvoiceLegacyLinkController(InvoicePublicLinkUseCase links, ObjectProvider<ModuleProvisioner> provisioner,
                                       InvoiceLinkRateLimiter limiter, PublicBaseUrl publicBaseUrl) {
        this.links = links;
        this.provisioner = provisioner;
        this.limiter = limiter;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping("/api/v1/invoices/public/{token}")
    public ResponseEntity<Void> legacy(@PathVariable("token") String token,
                                       jakarta.servlet.http.HttpServletRequest request) {
        String address = ClientIps.of(request);
        limiter.assertMissesAllowed(address);
        if (token == null || !UUID_TEXT.matcher(token).matches()) {
            limiter.recordMiss(address);
            throw new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Malformed legacy invoice link");
        }
        UUID uuid = UUID.fromString(token);
        LegacyInvoiceLinkRegistry.Target target;
        try {
            target = links.resolveLegacy(uuid);
        } catch (DomainException e) {
            limiter.recordMiss(address);
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        limiter.acquire("legacy:" + uuid);
        String schema = TenantSchema.validate(target.tenantSchema());
        TenantContext.setCurrentTenant(schema);
        try {
            ModuleProvisioner p = provisioner.getIfAvailable();
            if (p != null) {
                p.ensureProvisioned(schema, InvoicingModule.ID);
            }
            InvoicePublicLinkUseCase.Link link = links.legacyRedirect(uuid, target, publicBaseUrl.resolve(request));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(link.url()))
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .build();
        } catch (DomainException e) {
            if ("INVOICE_LINK_INVALID".equals(e.getDomainCode())) {
                limiter.recordMiss(address); // retired token or deleted document: a miss like an unknown token
            }
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

package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceLinkRotatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR23) — public links that last:
 * <ul>
 *   <li>a link is an HMAC token ({@link InvoiceLinkSigner}) bound to the tenant, the document, its expiry and the
 *       document's <b>link version</b>; its lifetime is the tenant's ({@link DocumentPreferences#linkDays()}: 30 days
 *       by default, 90 for a tenant imported from Bref);</li>
 *   <li>only a sendable document (issued / paid invoice, quote waiting for validation) gets a link or a rotation —
 *       anything else → 409, same French message as the sending;</li>
 *   <li>a <b>rotation</b> bumps the version (every link sent before stops opening → 404) and retires the document's old
 *       Bref token; audited ({@link InvoiceLinkRotatedEvent});</li>
 *   <li>an old Bref link ({@code /api/v1/invoices/public/<uuid>}) is resolved through {@link LegacyInvoiceLinkRegistry}
 *       (one primary-key read): unknown → 404, past its Bref expiry → 410, tenant not active → 404; then, tenant bound,
 *       the invoice must still hold that token ({@link #assertLegacyCurrent}).</li>
 * </ul>
 * Cost: link = one invoice read + one presentation read + one preferences read + at most one UPDATE (first share);
 * rotation = one locked read, one UPDATE … RETURNING (+ one DELETE by PK in public when a Bref token existed).
 */
@Service
public class InvoicePublicLinkUseCase {

    /** Path of the signed public link (JWT-free, see {@code SecurityConfig.PUBLIC_PATHS}). */
    public static final String SHARED_PATH = "/api/v1/invoices/shared?token=";

    private static final Logger log = LoggerFactory.getLogger(InvoicePublicLinkUseCase.class);

    /** A signed link and when it stops opening the document. */
    public record Link(String url, Instant expiresAt, int validityDays) {
    }

    private final InvoiceRepository invoices;
    private final InvoicePresentationRepository presentations;
    private final LegacyInvoiceLinkRegistry legacyLinks;
    private final InvoiceLinkSigner signer;
    private final CompanyIdentity companyIdentity;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InvoicePublicLinkUseCase(InvoiceRepository invoices, InvoicePresentationRepository presentations,
                                    LegacyInvoiceLinkRegistry legacyLinks, InvoiceLinkSigner signer,
                                    CompanyIdentity companyIdentity, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.presentations = presentations;
        this.legacyLinks = legacyLinks;
        this.signer = signer;
        this.companyIdentity = companyIdentity;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A fresh link to a sendable document (to share it from the phone, e.g. WhatsApp); the document is marked shared.
     * Not sendable → {@code INVOICE_INVALID_TRANSITION} (409).
     */
    @Transactional
    public Link link(UUID id, String baseUrl, String tenantSchema) {
        Invoice invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertSendable();
        int version = presentations.find(id).map(InvoicePresentation::linkVersion).orElse(0);
        presentations.markShared(id, clock.instant());
        return signedLink(id, version, baseUrl, tenantSchema);
    }

    /** The link of {@code invoiceId} at its current version, valid for the tenant's lifetime from now. */
    public Link signedLink(UUID invoiceId, String baseUrl, String tenantSchema) {
        int version = presentations.find(invoiceId).map(InvoicePresentation::linkVersion).orElse(0);
        return signedLink(invoiceId, version, baseUrl, tenantSchema);
    }

    /** The link of {@code invoiceId} at link version {@code version}, valid for the tenant's lifetime from now. */
    public Link signedLink(UUID invoiceId, int version, String baseUrl, String tenantSchema) {
        int days = presentations.preferences().linkDays();
        Instant expiresAt = clock.instant().plus(Duration.ofDays(days));
        String token = signer.sign(tenantSchema, invoiceId, expiresAt, version);
        return new Link(stripTrailingSlash(baseUrl) + SHARED_PATH + token, expiresAt, days);
    }

    /**
     * Rotation: every link already sent stops opening (404), the old Bref link too; returns the new link. Only a
     * sendable document (else 409, as the sending).
     */
    @Transactional
    public Link rotate(UUID id, String baseUrl, UUID actorId, String tenantSchema) {
        Invoice invoice = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertSendable();
        InvoicePresentation before = presentations.find(id).orElse(InvoicePresentation.NONE);
        Instant now = clock.instant();
        int version = presentations.rotateLink(id, now).orElseThrow(InvoiceQueryUseCase::notFound);
        if (before.legacyToken() != null) {
            legacyLinks.unregister(before.legacyToken());
        }
        presentations.markShared(id, now);
        events.publishEvent(new InvoiceLinkRotatedEvent(id, reference(invoice, before.legacyReference()),
                invoice.isQuote(), actorId, tenantSchema, now));
        log.info("INVOICE_LINK_ROTATED tenant={}", tenantSchema);
        return signedLink(id, version, baseUrl, tenantSchema);
    }

    /**
     * An old Bref link: the tenant and invoice it points to. Unknown token → 404 {@code INVOICE_LINK_INVALID}; past its
     * Bref expiry → 410 {@code INVOICE_LINK_EXPIRED}; tenant not ACTIVE → 404.
     */
    public LegacyInvoiceLinkRegistry.Target resolveLegacy(UUID token) {
        LegacyInvoiceLinkRegistry.Target target = legacyLinks.find(token).orElseThrow(InvoicePublicLinkUseCase::invalid);
        if (target.expiresAt() != null && !target.expiresAt().isAfter(clock.instant())) {
            throw new DomainException(ErrorCode.INVOICE_LINK_EXPIRED, "Legacy invoice link expired");
        }
        if (!companyIdentity.isActive(target.tenantSchema())) {
            throw invalid();
        }
        return target;
    }

    /**
     * Tenant bound: the invoice of {@code target} still holds the Bref token {@code token} (unique index read) — a
     * retired token whose registry row survived opens nothing. Else 404.
     */
    @Transactional(readOnly = true)
    public void assertLegacyCurrent(UUID token, LegacyInvoiceLinkRegistry.Target target) {
        UUID holder = presentations.findByLegacyToken(token).orElseThrow(InvoicePublicLinkUseCase::invalid);
        if (!holder.equals(target.invoiceId())) {
            throw invalid();
        }
    }

    /**
     * Story 24.4 import port — an imported invoice keeps its Bref link and reference: stored on the invoice (printed
     * next to the number) and in the cross-tenant registry, in the caller's transaction (tenant bound). A different
     * token than the one already attached retires the old one.
     *
     * @param brefToken      Bref's {@code publicToken} (UUID), {@code null} = no link to carry over
     * @param brefReference  {@code BRF-XXXXXXXX} (see {@link InvoicePresentation#legacyReferenceOf})
     * @param brefExpiresAt  Bref's {@code publicTokenExpiresAt}, {@code null} = no expiry of its own
     * @throws IllegalStateException the token is already attached to another invoice (never moved)
     */
    @Transactional
    public void attachLegacy(UUID invoiceId, UUID brefToken, String brefReference, Instant brefExpiresAt,
                             String tenantSchema) {
        if (brefReference != null && !InvoicePresentation.LEGACY_REFERENCE.matcher(brefReference).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid Bref reference",
                    Map.of("reason", "La référence Bref doit être de la forme BRF-XXXXXXXX"));
        }
        invoices.findByIdForUpdate(invoiceId).orElseThrow(InvoiceQueryUseCase::notFound);
        UUID previous = presentations.find(invoiceId).map(InvoicePresentation::legacyToken).orElse(null);
        if (previous != null && !Objects.equals(previous, brefToken)) {
            legacyLinks.unregister(previous);
        }
        if (brefToken != null) {
            legacyLinks.register(brefToken, TenantSchema.validate(tenantSchema), invoiceId, brefExpiresAt);
        }
        presentations.attachLegacy(invoiceId, brefToken, brefReference, clock.instant());
    }

    /** The reference printed for the document: number, {@code DV-…}, else the Bref reference, else « Brouillon ». */
    static String reference(Invoice invoice, String legacyReference) {
        String own = invoice.documentReference();
        if (own != null) return own;
        return legacyReference != null ? legacyReference : "Brouillon";
    }

    static DomainException invalid() {
        return new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Invoice link not valid");
    }

    /** {@code https://x/} → {@code https://x}; null → empty. Same rule as {@code shared.infrastructure.web.PublicBaseUrl} (Story 20.8). */
    public static String stripTrailingSlash(String base) {
        if (base == null) return "";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}

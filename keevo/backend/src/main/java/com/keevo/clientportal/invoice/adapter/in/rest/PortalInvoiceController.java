package com.keevo.clientportal.invoice.adapter.in.rest;

import com.keevo.clientportal.auth.application.PortalAuthService;
import com.keevo.clientportal.invoice.application.PortalInvoiceService;
import com.keevo.clientportal.invoice.domain.model.PortalCursor;
import com.keevo.shared.capability.portal.PortalDocuments.PortalDocument;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.infrastructure.web.PublicBaseUrl;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Story 21.7 — {@code GET /api/v1/portal/invoices}: the invoices issued to the signed-in client's phone by every
 * merchant that shows them, newest first, keyset-paged. Authenticated by the portal session token
 * ({@code Authorization: Bearer kvc_…}), never by a merchant JWT (401 {@code PORTAL_SESSION_INVALID}). Only the
 * minimum of each invoice: merchant, reference, dates, total, payment state (French label) and a 24-hour link to the
 * PDF (none for a cancelled invoice). No identifier is returned.
 */
@Tag(name = "Client portal", description = "End clients find their invoices by phone (Story 21.7)")
@EntitlementExempt("portail client : clients finaux hors compte commerçant, chaque commerçant filtré par son module")
@RestController
public class PortalInvoiceController {

    /** One invoice as the client sees it. */
    public record PortalInvoiceDto(String merchantName, String reference, LocalDate issueDate, LocalDate dueDate,
                                   long totalTtc, String paymentState, String paymentStateLabel, LinkDto link) {
    }

    /** Signed link to the PDF and when it stops opening. */
    public record LinkDto(String url, Instant expiresAt) {
    }

    /** One page and the token of the next one (null on the last page). */
    public record PageDto(List<PortalInvoiceDto> items, String nextCursor) {
    }

    private final PortalAuthService auth;
    private final PortalInvoiceService invoices;
    private final PublicBaseUrl publicBaseUrl;

    public PortalInvoiceController(PortalAuthService auth, PortalInvoiceService invoices, PublicBaseUrl publicBaseUrl) {
        this.auth = auth;
        this.invoices = invoices;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Operation(summary = "The signed-in client's invoices across merchants",
            description = "Bearer = portal session token. limit 1–50 (20 by default); cursor = nextCursor of the "
                    + "previous page. 401 PORTAL_SESSION_INVALID, 429 PORTAL_TOO_MANY_REQUESTS, 400 "
                    + "VALIDATION_ERROR (cursor or limit).")
    @SecurityRequirements
    @GetMapping("/api/v1/portal/invoices")
    public ResponseEntity<ApiResponseWrapper<PageDto>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        PortalAuthService.Client client = auth.authenticate(authorization, ClientIps.of(request));
        int size = limit == null ? PortalInvoiceService.DEFAULT_LIMIT : limit;
        if (size < 1 || size > PortalInvoiceService.MAX_LIMIT) {
            throw new InvalidRequestParameterException("limit", "portal page size out of range",
                    "Le nombre de factures par page doit être compris entre 1 et " + PortalInvoiceService.MAX_LIMIT);
        }
        PortalCursor after;
        try {
            after = PortalCursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("cursor", "invalid portal cursor",
                    "Cette page de factures n'existe plus : rechargez la liste.");
        }
        PortalInvoiceService.Page page = invoices.list(client.phone(), after, size, publicBaseUrl.resolve(request));
        List<PortalInvoiceDto> items = page.items().stream().map(PortalInvoiceController::dto).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(new PageDto(items, page.nextCursor())));
    }

    static PortalInvoiceDto dto(PortalDocument d) {
        return new PortalInvoiceDto(d.merchantName(), d.reference(), d.issueDate(), d.dueDate(), d.totalTtc(),
                d.paymentState(), d.paymentStateLabel(),
                d.linkUrl() == null ? null : new LinkDto(d.linkUrl(), d.linkExpiresAt()));
    }
}

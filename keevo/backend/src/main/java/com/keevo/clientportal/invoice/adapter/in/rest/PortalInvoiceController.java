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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Story 21.7 — {@code GET /api/v1/portal/invoices}: the invoices issued to the signed-in client's phone by every
 * merchant that shows them, newest first, keyset-paged. Authenticated by the portal session token
 * ({@code Authorization: Bearer kvc_…}), never by a merchant JWT (401 {@code PORTAL_SESSION_INVALID}). Each invoice:
 * merchant, reference, dates, total, payment state (French label), a 24-hour link to the PDF (none for a cancelled
 * invoice) and — reprise 11/11/2026, what Bref's screens show — the merchant's phone, the amounts paid and remaining,
 * the lines and the payments; the first page also carries the client's totals. No identifier, no client data, no
 * internal note is returned.
 */
@Tag(name = "Client portal", description = "End clients find their invoices by phone (Story 21.7)")
@EntitlementExempt("portail client : clients finaux hors compte commerçant, chaque commerçant filtré par son module")
@RestController
public class PortalInvoiceController {

    /**
     * One invoice as the client sees it. Reprise 11/11/2026 (Bref's detail screen, read from the list): the merchant's
     * phone, the amounts paid and remaining, the lines and the payments / instalments — appended, the 21.7 fields
     * unchanged.
     */
    public record PortalInvoiceDto(String merchantName, String reference, LocalDate issueDate, LocalDate dueDate,
                                   long totalTtc, String paymentState, String paymentStateLabel, LinkDto link,
                                   String merchantPhone, long amountPaid, long amountRemaining, List<LineDto> lines,
                                   List<PaymentDto> payments) {
    }

    /** Signed link to the PDF and when it stops opening. */
    public record LinkDto(String url, Instant expiresAt) {
    }

    /** One printed line: unit price as typed (excluding VAT when the invoice's prices exclude it), total with VAT. */
    public record LineDto(String reference, String description, BigDecimal quantity, long unitPrice, long totalTtc) {
    }

    /** A payment received ({@code paid}, {@code paidDate}) or an instalment ({@code dueDate}). */
    public record PaymentDto(long amount, LocalDate dueDate, boolean paid, LocalDate paidDate, String method) {
    }

    /** The client's totals (Bref's header): invoices listed and the amount of those not cancelled. */
    public record SummaryDto(long invoiceCount, long totalTtc) {
    }

    /** One page, the token of the next one (null on the last page) and, on the first page only, the totals. */
    public record PageDto(List<PortalInvoiceDto> items, String nextCursor, SummaryDto summary) {
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
                    + "previous page (summary on the first page only). 401 PORTAL_SESSION_INVALID, 429 "
                    + "PORTAL_TOO_MANY_REQUESTS, 400 "
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
        SummaryDto summary = page.summary() == null ? null
                : new SummaryDto(page.summary().invoiceCount(), page.summary().totalTtc());
        return ResponseEntity.ok(ApiResponseWrapper.ok(new PageDto(items, page.nextCursor(), summary)));
    }

    static PortalInvoiceDto dto(PortalDocument d) {
        return new PortalInvoiceDto(d.merchantName(), d.reference(), d.issueDate(), d.dueDate(), d.totalTtc(),
                d.paymentState(), d.paymentStateLabel(),
                d.linkUrl() == null ? null : new LinkDto(d.linkUrl(), d.linkExpiresAt()),
                d.merchantPhone(), d.amountPaid(), d.amountRemaining(),
                d.lines().stream().map(l -> new LineDto(l.reference(), l.description(), l.quantity(), l.unitPrice(),
                        l.totalTtc())).toList(),
                d.payments().stream().map(p -> new PaymentDto(p.amount(), p.dueDate(), p.paid(), p.paidDate(),
                        p.method())).toList());
    }
}

package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.SalesReadScope;
import com.keevo.commerce.sale.adapter.in.rest.dto.SaleSummaryDto;
import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort;
import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort.Summary;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Story P03 (parité V1) — {@code GET /api/v1/sales/summary}: the revenue of a period for any role, without the
 * invoicing module (a V1 company has it off since V50). Additive, read only.
 *
 * <p>Same rules as {@code /sales/history}: civil days in WAT ({@code from} / {@code to} inclusive, default today),
 * the scope decided by {@link SalesReadScope} (owner: any store or every store; a store member: his store, his own
 * sales unless {@code STORE_SALES_VIEW}). The figure is the single revenue source (20-9): a POS sale bridged to its
 * automatic invoice counts once, pending and cancelled sales never count.
 */
@RestController
@RequestMapping("/api/v1/sales/summary")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class SaleSummaryController {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    /** Longest period accepted (V1's custom range is 365 days; « depuis le début » sends its own bounds). */
    static final int MAX_DAYS = 3660;

    private final SalesSummaryPort summaryPort;
    private final SalesReadScope scope;

    public SaleSummaryController(SalesSummaryPort summaryPort, SalesReadScope scope) {
        this.summaryPort = summaryPort;
        this.scope = scope;
    }

    @RequiresEntitlement(value = "pos", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<SaleSummaryDto>> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID employeeId) {
        LocalDate today = LocalDate.now(WAT);
        LocalDate fromDate = parse(from, today);
        LocalDate toDate = parse(to, today);
        if (toDate.isBefore(fromDate) || fromDate.plusDays(MAX_DAYS).isBefore(toDate)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Période invalide.");
        }
        Instant fromInstant = fromDate.atStartOfDay(WAT).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(WAT).toInstant();

        SalesReadScope.Resolved resolved = scope.resolve(storeId, employeeId);
        Summary s = summaryPort.summarize(fromInstant, toInstant, resolved.store(), resolved.employeeFilter());
        return ResponseEntity.ok(ApiResponseWrapper.ok(new SaleSummaryDto(s.amount(), s.count(), s.paidAmount())));
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Date invalide.");
        }
    }
}

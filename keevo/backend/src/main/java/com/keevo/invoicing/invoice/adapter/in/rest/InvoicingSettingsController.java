package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoicingSettingsDto;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller profile printed on invoices — Story 14.1. Read: {@code INVOICE_VIEW}; edit: {@code INVOICE_MANAGE}
 * (owner decision). Changes never alter an issued invoice (its seller identity is a snapshot).
 */
@RestController
@RequestMapping("/api/v1/invoicing/settings")
public class InvoicingSettingsController {

    private final InvoicingSettingsUseCase settings;

    public InvoicingSettingsController(InvoicingSettingsUseCase settings) {
        this.settings = settings;
    }

    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<InvoicingSettingsDto>> get() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(InvoicingSettingsDto.fromDomain(settings.current())));
    }

    @RequiresPermission(InvoicingModule.INVOICE_MANAGE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PutMapping
    public ResponseEntity<ApiResponseWrapper<InvoicingSettingsDto>> update(
            @Valid @RequestBody InvoicingSettingsDto request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(InvoicingSettingsDto.fromDomain(
                settings.update(request.toDomain(), CurrentActor.actorId(), TenantContext.getCurrentTenant()))));
    }
}

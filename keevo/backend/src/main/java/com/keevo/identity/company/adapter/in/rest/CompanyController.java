package com.keevo.identity.company.adapter.in.rest;

import com.keevo.identity.company.adapter.in.rest.dto.CompanyDesignationRequestDto;
import com.keevo.identity.company.adapter.in.rest.dto.CompanyProfileDto;
import com.keevo.identity.company.application.service.CompanyProfileService;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * Company identity (Story 14.2, UX-DR5) — the designation and logo shown on invoices and export documents.
 *
 * <ul>
 *   <li>{@code GET /api/v1/account/company} (owner, employee) → {@code {designation, displayName, hasLogo}}</li>
 *   <li>{@code PUT /api/v1/account/company} {@code {designation}} (owner) — blank clears it</li>
 *   <li>{@code GET /api/v1/account/company/logo} (owner, employee) — the image bytes</li>
 *   <li>{@code PUT /api/v1/account/company/logo} multipart part {@code file} (owner) — PNG or JPEG, ≤ 200 KB</li>
 *   <li>{@code DELETE /api/v1/account/company/logo} (owner)</li>
 * </ul>
 * Core: never gated by a module.
 */
@EntitlementExempt("core — identité de l'entreprise")
@RestController
@RequestMapping("/api/v1/account/company")
public class CompanyController {

    private static final String OWNER_ONLY = "Seul le propriétaire peut modifier l'identité de l'entreprise";
    /** Declared types accepted up front; the bytes themselves are checked by the normalizer. */
    private static final Set<String> ACCEPTED_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

    private final CompanyProfileService service;

    public CompanyController(CompanyProfileService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<CompanyProfileDto>> get() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                CompanyProfileDto.from(service.get(TenantContext.getCurrentTenant()))));
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CompanyProfileDto>> update(
            @Valid @RequestBody CompanyDesignationRequestDto request) {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyProfileDto.from(service.updateDesignation(
                TenantContext.getCurrentTenant(), request.designation(), CurrentActor.actorId()))));
    }

    @GetMapping("/logo")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<byte[]> logo() {
        Logo logo = service.readLogo(TenantContext.getCurrentTenant());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.content());
    }

    @PutMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CompanyProfileDto>> uploadLogo(@RequestParam("file") MultipartFile file)
            throws IOException {
        CurrentActor.requireOwner(OWNER_ONLY);
        String declared = file.getContentType();
        if (declared != null && !ACCEPTED_TYPES.contains(declared.toLowerCase(java.util.Locale.ROOT))) {
            throw new DomainException(ErrorCode.COMPANY_LOGO_INVALID, "Declared type not accepted");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyProfileDto.from(service.uploadLogo(
                TenantContext.getCurrentTenant(), file.getBytes(), CurrentActor.actorId()))));
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CompanyProfileDto>> deleteLogo() {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyProfileDto.from(service.deleteLogo(
                TenantContext.getCurrentTenant(), CurrentActor.actorId()))));
    }
}

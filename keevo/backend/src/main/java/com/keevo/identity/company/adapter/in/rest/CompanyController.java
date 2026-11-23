package com.keevo.identity.company.adapter.in.rest;

import com.keevo.identity.company.adapter.in.rest.dto.CompanyDesignationRequestDto;
import com.keevo.identity.company.adapter.in.rest.dto.CompanyFullProfileDto;
import com.keevo.identity.company.adapter.in.rest.dto.CompanyProfileDto;
import com.keevo.identity.company.adapter.in.rest.dto.CompanyProfilePatchDto;
import com.keevo.identity.company.application.service.CompanyProfileService;
import com.keevo.identity.company.application.service.CompanySetupService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final CompanySetupService setup;

    public CompanyController(CompanyProfileService service, CompanySetupService setup) {
        this.service = service;
        this.setup = setup;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
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

    @GetMapping("/profile")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    public ResponseEntity<ApiResponseWrapper<CompanyFullProfileDto>> getProfile() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyFullProfileDto.from(
                service.getFullProfile(TenantContext.getCurrentTenant()))));
    }

    /** Partial update of the identity (absent ⇒ unchanged, empty ⇒ cleared). NIU and RCCM are never mandatory. */
    @PatchMapping("/profile")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CompanyFullProfileDto>> updateProfile(
            @Valid @RequestBody CompanyProfilePatchDto request) {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyFullProfileDto.from(service.updateProfile(
                TenantContext.getCurrentTenant(), request.toPatch(), CurrentActor.actorId()))));
    }

    /**
     * The owner finished the sign-up steps (organisation, first store). Idempotent. Story 22.19 (D2): also runs the
     * sector onboarding once (preferences, sector categories) with the sector of the company category.
     */
    @PostMapping("/setup/complete")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CompanyFullProfileDto>> completeSetup() {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(CompanyFullProfileDto.from(
                setup.complete(TenantContext.getCurrentTenant(), CurrentActor.actorId()))));
    }

    @GetMapping("/logo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
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

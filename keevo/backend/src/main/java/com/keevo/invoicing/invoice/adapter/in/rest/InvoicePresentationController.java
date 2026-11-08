package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.DocumentPreferencesDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceLayoutRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.PublicLinkDto;
import com.keevo.invoicing.invoice.application.usecase.InvoicePresentationUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage.StoredImage;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR21/22/23) — layout, signatures and public link of a document (invoice or
 * quote), and the tenant's document defaults. Gated by the {@code invoicing} module like {@link InvoiceController}.
 * <ul>
 *   <li>{@code PUT /api/v1/invoices/{id}/layout} {@code {invoiceTemplate?, ticketWidth?}} — any status
 *       ({@code INVOICE_CREATE}) → the invoice;</li>
 *   <li>{@code PUT /api/v1/invoices/{id}/signatures/{client|seller}} multipart {@code file} (PNG / JPEG ≤ 200 KB,
 *       {@code INVOICE_CREATE}) → the invoice; {@code DELETE} (draft only) → the invoice; {@code GET} → the image
 *       ({@code INVOICE_VIEW});</li>
 *   <li>{@code POST /api/v1/invoices/{id}/public-link} → {@code {url, expiresAt, validityDays}} (issued / paid invoice
 *       or quote waiting for validation, {@code INVOICE_SEND}); {@code POST …/public-link/rotate} → the new link, every
 *       older one stops opening ({@code INVOICE_SEND});</li>
 *   <li>{@code GET / PUT /api/v1/invoicing/settings/documents} {@code {invoiceTemplate, ticketWidth, publicLinkDays}}
 *       (read {@code INVOICE_VIEW}, write {@code INVOICE_MANAGE}).</li>
 * </ul>
 */
@RestController
public class InvoicePresentationController {

    private static final Set<String> ACCEPTED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "application/octet-stream");

    private final InvoicePresentationUseCase presentation;
    private final InvoicePublicLinkUseCase links;
    private final InvoiceResponseAssembler invoices;
    private final PublicBaseUrl publicBaseUrl;
    private final int maxBytes;

    public InvoicePresentationController(InvoicePresentationUseCase presentation, InvoicePublicLinkUseCase links,
                                         InvoiceResponseAssembler invoices, PublicBaseUrl publicBaseUrl,
                                         @Value("${keevo.invoice-signature.max-bytes:204800}") int maxBytes) {
        this.presentation = presentation;
        this.links = links;
        this.invoices = invoices;
        this.publicBaseUrl = publicBaseUrl;
        this.maxBytes = maxBytes;
    }

    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PutMapping("/api/v1/invoices/{id}/layout")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> layout(
            @PathVariable UUID id, @Valid @RequestBody InvoiceLayoutRequestDto request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(invoices.dto(presentation.updateLayout(id,
                InvoiceController.parseTemplate(request.invoiceTemplate()),
                InvoiceController.parseTicketWidth(request.ticketWidth())))));
    }

    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PutMapping(value = "/api/v1/invoices/{id}/signatures/{role}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> sign(@PathVariable UUID id,
                                                                       @PathVariable String role,
                                                                       @RequestParam("file") MultipartFile file)
            throws IOException {
        SignatureRole r = role(role);
        if (file.getSize() > maxBytes) { // refused before the upload is read into memory
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_TOO_LARGE,
                    "Signature of " + file.getSize() + " bytes over " + maxBytes);
        }
        if (!acceptedType(file.getContentType())) {
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_INVALID, "Declared type not accepted");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(invoices.dto(presentation.sign(id, r, file.getBytes(),
                TenantContext.getCurrentTenant()))));
    }

    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @DeleteMapping("/api/v1/invoices/{id}/signatures/{role}")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> unsign(@PathVariable UUID id,
                                                                         @PathVariable String role) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(invoices.dto(presentation.unsign(id, role(role),
                TenantContext.getCurrentTenant()))));
    }

    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/api/v1/invoices/{id}/signatures/{role}")
    public ResponseEntity<byte[]> signature(@PathVariable UUID id, @PathVariable String role) {
        StoredImage image = presentation.signature(id, role(role), TenantContext.getCurrentTenant());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.content());
    }

    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/api/v1/invoices/{id}/public-link")
    public ResponseEntity<ApiResponseWrapper<PublicLinkDto>> publicLink(@PathVariable UUID id,
                                                                        HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(PublicLinkDto.from(links.link(id,
                publicBaseUrl.resolve(http), TenantContext.getCurrentTenant()))));
    }

    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/api/v1/invoices/{id}/public-link/rotate")
    public ResponseEntity<ApiResponseWrapper<PublicLinkDto>> rotate(@PathVariable UUID id, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(PublicLinkDto.from(links.rotate(id,
                publicBaseUrl.resolve(http), TenantContext.getCurrentTenant()))));
    }

    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/api/v1/invoicing/settings/documents")
    public ResponseEntity<ApiResponseWrapper<DocumentPreferencesDto>> preferences() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(DocumentPreferencesDto.from(presentation.preferences())));
    }

    @RequiresPermission(InvoicingModule.INVOICE_MANAGE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PutMapping("/api/v1/invoicing/settings/documents")
    public ResponseEntity<ApiResponseWrapper<DocumentPreferencesDto>> updatePreferences(
            @Valid @RequestBody DocumentPreferencesDto request) {
        DocumentPreferences wanted = new DocumentPreferences(InvoiceController.parseTemplate(request.invoiceTemplate()),
                InvoiceController.parseTicketWidth(request.ticketWidth()), request.publicLinkDays());
        return ResponseEntity.ok(ApiResponseWrapper.ok(DocumentPreferencesDto.from(
                presentation.updatePreferences(wanted, CurrentActor.actorId()))));
    }

    /** {@code client} / {@code seller}; anything else → 400 French. */
    static SignatureRole role(String value) {
        return SignatureRole.parse(value).orElseThrow(() -> new InvalidRequestParameterException("role",
                "unknown signature role", "Signature inconnue : « client » ou « seller »"));
    }

    /** No declared type, or one of {@link #ACCEPTED_TYPES} (type/subtype, parameters ignored). */
    static boolean acceptedType(String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        try {
            int semicolon = declared.indexOf(';');
            MediaType type = MediaType.parseMediaType(semicolon < 0 ? declared : declared.substring(0, semicolon));
            return ACCEPTED_TYPES.contains((type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}

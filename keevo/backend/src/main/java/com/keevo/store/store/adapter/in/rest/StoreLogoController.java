package com.keevo.store.store.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.store.store.adapter.in.rest.dto.StoreResponseDto;
import com.keevo.store.store.domain.port.in.StoreLogoUseCase;
import com.keevo.store.store.domain.port.out.StoreLogoStorage.StoredLogo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Store logo (Story 19.12) — a separate controller because {@link StoreController} is OWNER-only as a whole while
 * the logo is readable by every role.
 *
 * <ul>
 *   <li>{@code GET /api/v1/stores/{storeId}/logo} (every tenant role) — the image bytes, cached 30 days (private):
 *       clients use the {@code logoUrl} given by the API, whose {@code ?v=} token changes with every upload</li>
 *   <li>{@code PUT /api/v1/stores/{storeId}/logo} multipart part {@code file} (owner) — PNG or JPEG,
 *       ≤ {@code keevo.store-logo.max-bytes} → the store</li>
 *   <li>{@code DELETE /api/v1/stores/{storeId}/logo} (owner) → the store</li>
 * </ul>
 */
@Tag(name = "Stores", description = "Store & Warehouse management")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/logo")
public class StoreLogoController {

    private static final String OWNER_ONLY = "Only OWNER can manage stores";
    /** Declared types accepted up front (type/subtype, parameters ignored); the bytes are checked by the normalizer. */
    private static final Set<String> ACCEPTED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "application/octet-stream");
    /** The URL carries a per-upload token ({@code ?v=}): the bytes behind one URL never change. */
    private static final CacheControl LOGO_CACHE = CacheControl.maxAge(Duration.ofDays(30)).cachePrivate();

    private final StoreLogoUseCase storeLogo;
    private final int maxBytes;

    public StoreLogoController(StoreLogoUseCase storeLogo,
                               @Value("${keevo.store-logo.max-bytes:204800}") int maxBytes) {
        this.storeLogo = storeLogo;
        this.maxBytes = maxBytes;
    }

    @Operation(summary = "Read a store's logo (every role)")
    @RequiresEntitlement(value = "store", access = RequiresEntitlement.Access.READ)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @GetMapping
    public ResponseEntity<byte[]> logo(@PathVariable UUID storeId) {
        StoredLogo logo = storeLogo.read(storeId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(LOGO_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.content());
    }

    @Operation(summary = "Set or replace a store's logo (PNG or JPEG)")
    @RequiresEntitlement("store")
    @PreAuthorize("hasRole('OWNER')")
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<StoreResponseDto>> upload(@PathVariable UUID storeId,
                                                                       @RequestParam("file") MultipartFile file)
            throws IOException {
        CurrentActor.requireOwner(OWNER_ONLY);
        if (file.getSize() > maxBytes) { // refused before the upload is read into memory
            throw new DomainException(ErrorCode.STORE_LOGO_TOO_LARGE,
                    "Store logo of " + file.getSize() + " bytes over " + maxBytes);
        }
        if (!acceptedType(file.getContentType())) {
            throw new DomainException(ErrorCode.STORE_LOGO_INVALID, "Declared type not accepted");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(StoreResponseDto.fromDomain(
                storeLogo.upload(storeId, file.getBytes(), CurrentActor.actorId()))));
    }

    @Operation(summary = "Remove a store's logo")
    @RequiresEntitlement("store")
    @PreAuthorize("hasRole('OWNER')")
    @DeleteMapping
    public ResponseEntity<ApiResponseWrapper<StoreResponseDto>> delete(@PathVariable UUID storeId) {
        CurrentActor.requireOwner(OWNER_ONLY);
        return ResponseEntity.ok(ApiResponseWrapper.ok(StoreResponseDto.fromDomain(
                storeLogo.delete(storeId, CurrentActor.actorId()))));
    }

    /** No declared type, or one of {@link #ACCEPTED_TYPES} (type/subtype, case and parameters ignored). */
    static boolean acceptedType(String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        try {
            int semicolon = declared.indexOf(';'); // parameters ignored (an odd charset must not refuse an image)
            MediaType type = MediaType.parseMediaType(semicolon < 0 ? declared : declared.substring(0, semicolon));
            return ACCEPTED_TYPES.contains((type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}

package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.product.application.usecase.ProductPhotoUseCase;
import com.keevo.catalog.product.application.usecase.ProductPhotoUseCase.Actor;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage.StoredPhoto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Product photo thumbnails (FR21).
 *
 * <ul>
 *   <li>{@code POST /api/v1/products/{id}/photo} — multipart part {@code file} (JPEG, PNG or
 *       WebP, ≤ 200 KB) → {@code {photoUrl}}</li>
 *   <li>{@code GET /api/v1/products/{id}/photo} — the image bytes, authenticated: the tenant is
 *       the one of the session, so a product id of another tenant answers 404</li>
 *   <li>{@code DELETE /api/v1/products/{id}/photo} — « Supprimer la photo »</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/products/{id}/photo")
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class ProductPhotoController {

    /** Declared types accepted up front; the bytes themselves are checked by the normalizer. */
    private static final Set<String> ACCEPTED_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp",
            MediaType.APPLICATION_OCTET_STREAM_VALUE);

    private final ProductPhotoUseCase useCase;

    public ProductPhotoController(ProductPhotoUseCase useCase) {
        this.useCase = useCase;
    }

    @RequiresEntitlement("catalog")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> upload(
            @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        String declared = file.getContentType();
        if (declared != null && !ACCEPTED_TYPES.contains(declared.toLowerCase())) {
            throw new DomainException(ErrorCode.PRODUCT_PHOTO_INVALID, "Declared type not accepted");
        }
        String url = useCase.upload(TenantContext.getCurrentTenant(), id, file.getBytes(), actor());
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("photoUrl", url)));
    }

    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<byte[]> read(@PathVariable UUID id) {
        StoredPhoto photo = useCase.read(TenantContext.getCurrentTenant(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                // Private: authenticated, per-tenant content. The URL carries ?v=, so a new
                // photo is a new URL and a long cache is safe.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.content());
    }

    @RequiresEntitlement("catalog")
    @DeleteMapping
    public ResponseEntity<ApiResponseWrapper<Void>> delete(@PathVariable UUID id) {
        useCase.delete(TenantContext.getCurrentTenant(), id, actor());
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    private static Actor actor() {
        return new Actor(CurrentActor.actorId(), CurrentActor.role(), CurrentActor.actorLabel());
    }
}

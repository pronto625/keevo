package com.keevo.upload.adapter.in.rest;

import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.upload.application.ImageUploadService;
import com.keevo.upload.domain.port.out.ImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Envoi d'une image (api-bref : {@code POST /v1/uploads}) : l'image est déposée chez Cloudinary et la réponse donne son
 * lien public. Tous les rôles qui écrivent (propriétaire, gérant, employé).
 */
@EntitlementExempt("core: envoi de fichiers")
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final ImageUploadService uploads;

    public UploadController(ImageUploadService uploads) {
        this.uploads = uploads;
    }

    /** {@code url}: lien https de l'image ; {@code publicId}: son identifiant chez le stockage. */
    public record UploadDto(String url, String publicId) {
    }

    /**
     * POST /api/v1/uploads (multipart {@code file}, {@code folder?}) → 201 {@code {url, publicId}}. 400 fichier absent,
     * pas une image, plus de 5 Mo ou dossier invalide ; 429 {@code UPLOAD_TOO_MANY_REQUESTS} (30/min par entreprise) ; 502 {@code UPLOAD_FAILED} ; 503 {@code UPLOAD_UNAVAILABLE}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<UploadDto>> upload(@RequestParam(value = "file", required = false) MultipartFile file,
                                                                @RequestParam(value = "folder", required = false) String folder)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestParameterException("file", "required", "Joignez une image");
        }
        ImageStorage.StoredImage image = uploads.upload(file.getBytes(), file.getContentType(), folder,
                com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(new UploadDto(image.url(), image.publicId())));
    }
}

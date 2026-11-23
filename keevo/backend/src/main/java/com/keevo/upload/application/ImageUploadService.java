package com.keevo.upload.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.upload.domain.port.out.ImageStorage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dépôt d'une image envoyée par l'app (comme {@code POST /v1/uploads} d'api-bref) : 5 Mo au plus, formats d'image
 * courants (le SVG est exclu : il peut embarquer du script), type vérifié aussi sur les premiers octets, dossier de
 * rangement en lettres, chiffres, tirets. Story 22.19 : rangé sous le code de l'entreprise, 30 envois par minute et
 * par entreprise, HEIC reconnu à sa marque (une vidéo MP4 ou MOV est refusée).
 */
@Service
public class ImageUploadService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String DEFAULT_FOLDER = "misc";
    private static final Pattern FOLDER = Pattern.compile("^[a-zA-Z0-9_-]{1,40}$");
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
            "image/heic", "image/heif");

    /** Story 22.19 (D10) — the ISO-BMFF brands of a HEIC / HEIF image (an MP4 or a MOV is refused). */
    private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "mif1", "msf1");

    private final ImageStorage storage;
    private final UploadRateLimiter limiter;
    /** Story 22.19 (D10) — schema → public tenant code (one-row lookup), never guessed from the schema name. */
    private final java.util.function.Function<String, java.util.Optional<String>> tenantCodes;

    @org.springframework.beans.factory.annotation.Autowired
    public ImageUploadService(ImageStorage storage, UploadRateLimiter limiter,
                              com.keevo.shared.module.application.port.TenantModuleProvisioningPort tenants) {
        this(storage, limiter, tenants::tenantCode);
    }

    ImageUploadService(ImageStorage storage, UploadRateLimiter limiter,
                       java.util.function.Function<String, java.util.Optional<String>> tenantCodes) {
        this.storage = storage;
        this.limiter = limiter;
        this.tenantCodes = tenantCodes;
    }

    /**
     * @param tenantSchema the uploading company (Story 22.19, D10): its images go to {@code bref/<tenant code>/<folder>}
     *                     and count towards its {@value UploadRateLimiter#PER_MINUTE} uploads per minute
     */
    public ImageStorage.StoredImage upload(byte[] content, String contentType, String folder, String tenantSchema) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (!TYPES.contains(type) || content == null || !hasImageSignature(content)) {
            throw new InvalidRequestParameterException("file", "type",
                    "Le fichier doit être une image (JPEG, PNG, WebP, GIF ou HEIC)");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidRequestParameterException("file", "size", "L'image doit faire 5 Mo au plus");
        }
        String target = folder == null || folder.isBlank() ? DEFAULT_FOLDER : folder.strip();
        if (!FOLDER.matcher(target).matches()) {
            throw new InvalidRequestParameterException("folder", "format", "Dossier invalide");
        }
        if (tenantSchema == null || tenantSchema.isBlank()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Upload without a company", Map.of());
        }
        if (!storage.available()) {
            throw new DomainException(ErrorCode.UPLOAD_UNAVAILABLE, "Image storage not configured", Map.of());
        }
        String code = tenantCodes.apply(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "Upload for an unknown company", Map.of()));
        // counted once storage is there; a failed upload gives its slot back
        if (!limiter.tryAcquire(tenantSchema)) {
            throw new DomainException(ErrorCode.UPLOAD_TOO_MANY_REQUESTS, "Upload limit reached",
                    Map.of("limitPerMinute", UploadRateLimiter.PER_MINUTE));
        }
        try {
            return storage.store(content, code + "/" + target);
        } catch (IOException | RuntimeException e) {
            limiter.release(tenantSchema);
            if (e instanceof RuntimeException r) {
                throw r;
            }
            throw new DomainException(ErrorCode.UPLOAD_FAILED, "Image storage failed", Map.of());
        }
    }

    /** Le type MIME vient du client : les premiers octets disent ce que le fichier est vraiment. */
    static boolean hasImageSignature(byte[] b) {
        if (b.length < 12) {
            return false;
        }
        boolean jpeg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                && b[10] == 'B' && b[11] == 'P';
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean heic = b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && HEIF_BRANDS.contains(new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        return jpeg || png || webp || gif || heic;
    }
}

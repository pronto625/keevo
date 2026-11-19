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
 * rangement en lettres, chiffres, tirets.
 */
@Service
public class ImageUploadService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String DEFAULT_FOLDER = "misc";
    private static final Pattern FOLDER = Pattern.compile("^[a-zA-Z0-9_-]{1,40}$");
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
            "image/heic", "image/heif");

    private final ImageStorage storage;

    public ImageUploadService(ImageStorage storage) {
        this.storage = storage;
    }

    public ImageStorage.StoredImage upload(byte[] content, String contentType, String folder) {
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
        if (!storage.available()) {
            throw new DomainException(ErrorCode.UPLOAD_UNAVAILABLE, "Image storage not configured", Map.of());
        }
        try {
            return storage.store(content, target);
        } catch (IOException e) {
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
        boolean heic = b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
        return jpeg || png || webp || gif || heic;
    }
}

package com.keevo.whatsappcloud.inbox.adapter.in.rest;

import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * Story 21.6 — the media files Meta accepts in a template header (21.5 rules): JPEG / PNG ≤ 5 MB, MP4 / PDF ≤ 10 MB
 * (the API's multipart limit); the file name reduced to safe characters.
 */
public final class MediaUploads {

    public static final long MAX_IMAGE = 5L * 1024 * 1024;
    public static final long MAX_MEDIA = 10L * 1024 * 1024;

    /** A checked upload: its bytes, media type and safe name. */
    public record Media(byte[] content, String type, String filename) {
    }

    private MediaUploads() {
    }

    public static Media check(MultipartFile file) throws IOException {
        if (file == null) {
            throw new InvalidRequestParameterException("file", "required", "Joignez un fichier");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        long max = switch (type) {
            case "image/jpeg", "image/png" -> MAX_IMAGE;
            case "video/mp4", "application/pdf" -> MAX_MEDIA;
            default -> throw new InvalidRequestParameterException("file", "type",
                    "Formats acceptés : image JPEG ou PNG, vidéo MP4, document PDF");
        };
        if (file.isEmpty() || file.getSize() > max) {
            throw new InvalidRequestParameterException("file", "size", max == MAX_IMAGE
                    ? "L'image doit faire 5 Mo au plus" : "Le fichier doit faire 10 Mo au plus");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "media"
                : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
        return new Media(file.getBytes(), type, name.length() > 100 ? name.substring(0, 100) : name);
    }
}

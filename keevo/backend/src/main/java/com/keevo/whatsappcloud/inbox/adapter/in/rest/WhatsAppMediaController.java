package com.keevo.whatsappcloud.inbox.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
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
 * Story 21.6 — the media of a template's IMAGE / VIDEO / DOCUMENT header, uploaded to the company number before a
 * send (inbox template, campaign): owner only, module whatsapp-cloud (WRITE). The returned {@code mediaId} is kept by
 * the app and sent back as {@code headerMediaId}; never shown.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/media")
@PreAuthorize("hasRole('OWNER')")
public class WhatsAppMediaController {

    public record MediaView(String mediaId) {
    }

    private final WhatsAppInboxService inbox;

    public WhatsAppMediaController(WhatsAppInboxService inbox) {
        this.inbox = inbox;
    }

    /**
     * POST /api/v1/whatsapp/media (multipart {@code file}: JPEG / PNG ≤ 5 MB, MP4 / PDF ≤ 10 MB) → {@code {mediaId}};
     * 409 {@code WHATSAPP_NOT_CONNECTED}, 502 {@code WHATSAPP_CLOUD_META_ERROR}, 400 French.
     */
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<MediaView>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        MediaUploads.Media media = MediaUploads.check(file);
        return ResponseEntity.ok(ApiResponseWrapper.ok(new MediaView(inbox.uploadMedia(TenantContext.requireCurrent(),
                media.content(), media.type(), media.filename()))));
    }
}

package com.keevo.upload.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.upload.domain.port.out.ImageStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ImageUploadService — Story 22.19 (D10)")
class ImageUploadService2219Test {

    private final ImageStorage storage = mock(ImageStorage.class);
    /** The tenant code as public.tenants holds it (never derived from the schema name). */
    private static final java.util.function.Function<String, java.util.Optional<String>> CODES =
            schema -> java.util.Optional.of(schema.equals("kv_ab12cd") ? "KV-AB12CD" : "KV-" + schema.hashCode());

    private static byte[] ftyp(String brand) {
        byte[] b = new byte[16];
        b[3] = 0x18;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, b, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d};
    }

    @Test
    @DisplayName("HEIC recognised by its brand; an MP4 / MOV (isom, qt) is refused")
    void heicBrands() {
        assertThat(ImageUploadService.hasImageSignature(ftyp("heic"))).isTrue();
        assertThat(ImageUploadService.hasImageSignature(ftyp("mif1"))).isTrue();
        assertThat(ImageUploadService.hasImageSignature(ftyp("isom"))).isFalse();
        assertThat(ImageUploadService.hasImageSignature(ftyp("qt  "))).isFalse();
    }

    @Test
    @DisplayName("stored under the company code read from the tenant (bref/<KV-CODE>/<folder>)")
    void tenantFolder() throws Exception {
        when(storage.available()).thenReturn(true);
        when(storage.store(any(), any())).thenReturn(new ImageStorage.StoredImage("https://x/y.png", "y"));
        var service = new ImageUploadService(storage, new UploadRateLimiter(Clock.systemUTC()), CODES);

        service.upload(png(), "image/png", "products", "kv_ab12cd");

        verify(storage).store(any(), eq("KV-AB12CD/products"));
    }

    @Test
    @DisplayName("31st upload of the minute → UPLOAD_TOO_MANY_REQUESTS; another company is not affected")
    void rateLimited() throws Exception {
        when(storage.available()).thenReturn(true);
        when(storage.store(any(), any())).thenReturn(new ImageStorage.StoredImage("https://x/y.png", "y"));
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC);
        var service = new ImageUploadService(storage, new UploadRateLimiter(clock), CODES);
        for (int i = 0; i < 30; i++) {
            service.upload(png(), "image/png", null, "kv_rate01");
        }
        assertThatThrownBy(() -> service.upload(png(), "image/png", null, "kv_rate01"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("domainCode", "UPLOAD_TOO_MANY_REQUESTS");
        service.upload(png(), "image/png", null, "kv_rate02");
    }

    @Test
    @DisplayName("a video under an image MIME type is refused before anything is counted or stored")
    void videoRefused() throws Exception {
        var service = new ImageUploadService(storage, new UploadRateLimiter(Clock.systemUTC()), CODES);
        assertThatThrownBy(() -> service.upload(ftyp("isom"), "image/heic", null, "kv_vid01"))
                .isInstanceOf(InvalidRequestParameterException.class);
        verify(storage, never()).store(any(), any());
    }

    @Test
    @DisplayName("storage down or failing: no upload counted (30 failures leave the next upload allowed)")
    void failuresNotCounted() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC);
        var service = new ImageUploadService(storage, new UploadRateLimiter(clock), CODES);
        when(storage.available()).thenReturn(true);
        when(storage.store(any(), any())).thenThrow(new java.io.IOException("down"));
        for (int i = 0; i < 31; i++) {
            assertThatThrownBy(() -> service.upload(png(), "image/png", null, "kv_fail01"))
                    .hasFieldOrPropertyWithValue("domainCode", "UPLOAD_FAILED");
        }
        when(storage.available()).thenReturn(false);
        assertThatThrownBy(() -> service.upload(png(), "image/png", null, "kv_fail01"))
                .hasFieldOrPropertyWithValue("domainCode", "UPLOAD_UNAVAILABLE");
    }
}

package com.keevo.store.store.adapter.out.image;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImageIoStoreLogoNormalizer — shared re-encoder (Story 19.12)")
class ImageIoStoreLogoNormalizerTest {

    private final ImageIoStoreLogoNormalizer normalizer = new ImageIoStoreLogoNormalizer(600, 204800);

    private static byte[] image(String format, int w, int h, int type) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(w, h, type), format, out)).isTrue();
        return out.toByteArray();
    }

    private static String code(Throwable e) {
        return ((DomainException) e).getDomainCode();
    }

    @Test
    @DisplayName("a PNG stays a PNG, a JPEG stays a JPEG; the longest side is capped at 600")
    void pngAndJpeg() throws Exception {
        var png = normalizer.normalize(image("png", 1200, 300, BufferedImage.TYPE_INT_ARGB));
        assertThat(png.contentType()).isEqualTo("image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png.content()));
        assertThat(decoded.getWidth()).isEqualTo(600);
        assertThat(decoded.getHeight()).isEqualTo(150);

        var jpeg = normalizer.normalize(image("jpg", 100, 80, BufferedImage.TYPE_INT_RGB));
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("GIF, text, corrupt bytes → STORE_LOGO_INVALID")
    void invalid() throws Exception {
        byte[] gif = image("gif", 10, 10, BufferedImage.TYPE_INT_RGB);
        byte[] png = image("png", 50, 50, BufferedImage.TYPE_INT_ARGB);
        byte[] corrupt = java.util.Arrays.copyOf(png, 30);
        for (byte[] bad : new byte[][]{gif, "hello, not an image at all".getBytes(StandardCharsets.UTF_8), corrupt,
                new byte[0], null}) {
            assertThatThrownBy(() -> normalizer.normalize(bad)).satisfies(e ->
                    assertThat(code(e)).isEqualTo("STORE_LOGO_INVALID"));
        }
    }

    @Test
    @DisplayName("a re-encoded logo still heavier than max-bytes → STORE_LOGO_TOO_HEAVY")
    void tooHeavyAfterReencoding() throws Exception {
        var tiny = new ImageIoStoreLogoNormalizer(600, 10);
        assertThatThrownBy(() -> tiny.normalize(image("png", 50, 50, BufferedImage.TYPE_INT_ARGB)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_LOGO_TOO_HEAVY"));
    }
}

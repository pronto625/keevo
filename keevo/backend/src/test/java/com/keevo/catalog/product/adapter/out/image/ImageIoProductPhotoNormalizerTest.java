package com.keevo.catalog.product.adapter.out.image;

import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer.NormalizedPhoto;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImageIoProductPhotoNormalizer")
class ImageIoProductPhotoNormalizerTest {

    private final ImageIoProductPhotoNormalizer normalizer = new ImageIoProductPhotoNormalizer(400);

    @Test
    @DisplayName("a large JPEG is downscaled to 400 px on its longest side and stays a JPEG")
    void jpegDownscaled() throws Exception {
        NormalizedPhoto out = normalizer.normalize(image("jpg", 1200, 800, BufferedImage.TYPE_INT_RGB));

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.content()));
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(267);
    }

    @Test
    @DisplayName("a small image is never upscaled")
    void smallNotUpscaled() throws Exception {
        NormalizedPhoto out = normalizer.normalize(image("jpg", 120, 90, BufferedImage.TYPE_INT_RGB));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.content()));
        assertThat(decoded.getWidth()).isEqualTo(120);
        assertThat(decoded.getHeight()).isEqualTo(90);
    }

    @Test
    @DisplayName("a PNG (with transparency) is re-encoded as an opaque JPEG")
    void pngToJpeg() throws Exception {
        NormalizedPhoto out = normalizer.normalize(image("png", 300, 300, BufferedImage.TYPE_INT_ARGB));

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIoProductPhotoNormalizer.isJpeg(out.content())).isTrue();
    }

    @Test
    @DisplayName("metadata is stripped: an EXIF/comment block of the upload is not in the output")
    void metadataStripped() throws Exception {
        byte[] jpeg = image("jpg", 200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] marker = "GPS-SECRET-LOCATION".getBytes(StandardCharsets.US_ASCII);
        // Insert a COM segment (FF FE) right after SOI.
        ByteArrayOutputStream withComment = new ByteArrayOutputStream();
        withComment.write(jpeg, 0, 2);
        withComment.write(0xFF);
        withComment.write(0xFE);
        int len = marker.length + 2;
        withComment.write((len >> 8) & 0xFF);
        withComment.write(len & 0xFF);
        withComment.write(marker);
        withComment.write(jpeg, 2, jpeg.length - 2);

        NormalizedPhoto out = normalizer.normalize(withComment.toByteArray());

        assertThat(new String(out.content(), StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET-LOCATION");
    }

    @Test
    @DisplayName("a WebP (RIFF/WEBP header) is accepted as is")
    void webpPassThrough() {
        byte[] webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ....".getBytes(StandardCharsets.ISO_8859_1);

        NormalizedPhoto out = normalizer.normalize(webp);

        assertThat(out.contentType()).isEqualTo("image/webp");
        assertThat(out.content()).isEqualTo(webp);
    }

    @Test
    @DisplayName("not an image (GIF, text, truncated JPEG) → PRODUCT_PHOTO_INVALID")
    void invalidRejected() throws Exception {
        assertInvalid("GIF89a-not-accepted".getBytes(StandardCharsets.US_ASCII));
        assertInvalid("hello world, not an image".getBytes(StandardCharsets.US_ASCII));
        byte[] jpeg = image("jpg", 200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] truncated = new byte[20];
        System.arraycopy(jpeg, 0, truncated, 0, 20);
        assertInvalid(truncated);
        assertInvalid(new byte[0]);
    }

    @Test
    @DisplayName("a declared size above 4096 px is refused before decoding")
    void hugeDimensionsRejected() throws Exception {
        assertInvalid(image("png", 5000, 10, BufferedImage.TYPE_INT_RGB));
    }

    private void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> normalizer.normalize(bytes))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo("PRODUCT_PHOTO_INVALID");
    }

    private static byte[] image(String format, int w, int h, int type) throws Exception {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}

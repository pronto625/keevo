package com.keevo.catalog.product.adapter.out.image;

import com.keevo.catalog.product.adapter.out.config.ProductPhotoProperties;
import com.keevo.catalog.product.domain.port.out.ProductPhotoNormalizer;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * JDK ImageIO normalizer. The format is recognised by its magic bytes (the declared content
 * type is never trusted). JPEG and PNG are decoded and re-encoded as JPEG (quality 0.75, white
 * background under transparency, longest side capped at {@code keevo.product-photo.max-dimension})
 * — which drops every metadata block (EXIF: GPS position, device…). WebP cannot be decoded by
 * the JDK: its RIFF/WEBP header is checked and it is stored as is (the app never sends WebP; the
 * upload size cap still bounds it).
 */
@Component
public class ImageIoProductPhotoNormalizer implements ProductPhotoNormalizer {

    /** Decoding bound: a larger declared image is refused before any pixel is allocated. */
    static final int MAX_SOURCE_DIMENSION = 4096;
    static final float JPEG_QUALITY = 0.75f;

    private final int maxDimension;

    @Autowired
    public ImageIoProductPhotoNormalizer(ProductPhotoProperties properties) {
        this(properties.getMaxDimension());
    }

    ImageIoProductPhotoNormalizer(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    @Override
    public NormalizedPhoto normalize(byte[] upload) {
        if (upload == null || upload.length < 12) throw invalid();
        if (isWebp(upload)) return new NormalizedPhoto(upload, "image/webp");
        if (!isJpeg(upload) && !isPng(upload)) throw invalid();
        try {
            BufferedImage source = decode(upload);
            return new NormalizedPhoto(encodeJpeg(fit(source)), "image/jpeg");
        } catch (DomainException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // ignoreMetadata: never parsed, never kept
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SOURCE_DIMENSION || h > MAX_SOURCE_DIMENSION) throw invalid();
                BufferedImage image = reader.read(0);
                if (image == null) throw invalid();
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Downscale to fit {@link #maxDimension} (never upscale) onto an opaque RGB canvas. */
    private BufferedImage fit(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage target = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    static boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        return (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    static boolean isWebp(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.PRODUCT_PHOTO_INVALID, "Unsupported or corrupt product photo");
    }
}

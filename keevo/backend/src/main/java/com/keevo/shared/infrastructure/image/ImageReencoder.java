package com.keevo.shared.infrastructure.image;

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
 * JDK ImageIO logo re-encoder — the reusable core of the company logo (Story 14.2) and store logo (Story 19.12)
 * normalizers; no module dependency. The format is recognised by its magic bytes (a declared content type is never
 * trusted): a PNG stays a PNG (transparency kept), a JPEG stays a JPEG (quality 0.85); both are decoded and
 * re-encoded — which drops every metadata block — with the longest side capped at {@code maxDimension} (never
 * upscaled). An opaque PNG heavier than the caller's threshold is re-encoded as JPEG. Anything else (another format,
 * corrupt bytes, a side over {@link #MAX_SOURCE_DIMENSION}) → {@link InvalidImageException}, which the caller maps to
 * its own error code. Cost: one decode and at most two encodes of an image bounded by the upload size cap.
 */
public final class ImageReencoder {

    /** Decoding bound: a larger declared image is refused before any pixel is allocated. */
    public static final int MAX_SOURCE_DIMENSION = 4096;
    public static final float JPEG_QUALITY = 0.85f;

    /** A re-encoded image: {@code image/png} or {@code image/jpeg}. */
    public record Image(byte[] content, String contentType) {
    }

    /** Not a PNG / JPEG image, or a corrupt / oversized one. */
    public static final class InvalidImageException extends RuntimeException {
        InvalidImageException() {
            super("Unsupported or corrupt image", null, false, false);
        }
    }

    private final int maxDimension;

    public ImageReencoder(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    /**
     * @param opaquePngToJpegAbove an opaque PNG whose re-encoded size is over this many bytes is re-encoded as JPEG
     * @throws InvalidImageException anything but a readable PNG / JPEG within {@link #MAX_SOURCE_DIMENSION}
     */
    public Image reencode(byte[] upload, int opaquePngToJpegAbove) {
        if (upload == null || upload.length < 12) throw new InvalidImageException();
        boolean png = isPng(upload);
        if (!png && !isJpeg(upload)) throw new InvalidImageException();
        try {
            BufferedImage source = decode(upload);
            if (png) {
                BufferedImage fitted = fit(source, true);
                byte[] bytes = encodePng(fitted);
                return bytes.length > opaquePngToJpegAbove && !hasTransparency(fitted)
                        ? new Image(encodeJpeg(fit(source, false)), "image/jpeg")
                        : new Image(bytes, "image/png");
            }
            return new Image(encodeJpeg(fit(source, false)), "image/jpeg");
        } catch (InvalidImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException();
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new InvalidImageException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SOURCE_DIMENSION || h > MAX_SOURCE_DIMENSION) {
                    throw new InvalidImageException();
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new InvalidImageException();
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Downscale to fit {@link #maxDimension} (never upscale); ARGB for a PNG, white RGB canvas for a JPEG. */
    private BufferedImage fit(BufferedImage source, boolean alpha) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage target = new BufferedImage(tw, th, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, tw, th);
            }
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Any pixel not fully opaque. */
    public static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) return true;
            }
        }
        return false;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG writer");
        return out.toByteArray();
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

    public static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    public static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }
}

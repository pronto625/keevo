package com.keevo.identity.company.adapter.out.image;

import com.keevo.identity.company.adapter.out.config.CompanyLogoProperties;
import com.keevo.identity.company.domain.port.out.CompanyLogoNormalizer;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
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
 * JDK ImageIO logo normalizer (Story 14.2). The format is recognised by its magic bytes (the declared content type
 * is never trusted): a PNG stays a PNG (transparency kept), a JPEG stays a JPEG (quality 0.85); both are decoded and
 * re-encoded — which drops every metadata block — with the longest side capped at
 * {@code keevo.company-logo.max-dimension} (600 px). Anything else → {@code COMPANY_LOGO_INVALID}. The result must
 * stay under {@link #MAX_NORMALIZED_BYTES} (300 KB, inlined in every PDF): an opaque PNG that is heavier is re-encoded
 * as JPEG; still too heavy (or transparent) → {@code COMPANY_LOGO_TOO_HEAVY} (422).
 */
@Component
public class ImageIoCompanyLogoNormalizer implements CompanyLogoNormalizer {

    /** Decoding bound: a larger declared image is refused before any pixel is allocated. */
    static final int MAX_SOURCE_DIMENSION = 4096;
    static final float JPEG_QUALITY = 0.85f;
    /** Largest stored logo after re-encoding: it is inlined in every invoice / export PDF. */
    public static final int MAX_NORMALIZED_BYTES = 300 * 1024;

    private final int maxDimension;

    @Autowired
    public ImageIoCompanyLogoNormalizer(CompanyLogoProperties properties) {
        this(properties.getMaxDimension());
    }

    public ImageIoCompanyLogoNormalizer(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    @Override
    public Logo normalize(byte[] upload) {
        if (upload == null || upload.length < 12) throw invalid();
        boolean png = isPng(upload);
        if (!png && !isJpeg(upload)) throw invalid();
        try {
            BufferedImage source = decode(upload);
            Logo logo;
            if (png) {
                BufferedImage fitted = fit(source, true);
                byte[] bytes = encodePng(fitted);
                logo = bytes.length > MAX_NORMALIZED_BYTES && !hasTransparency(fitted)
                        ? new Logo(encodeJpeg(fit(source, false)), "image/jpeg")
                        : new Logo(bytes, "image/png");
            } else {
                logo = new Logo(encodeJpeg(fit(source, false)), "image/jpeg");
            }
            if (logo.content().length > MAX_NORMALIZED_BYTES) {
                throw new DomainException(ErrorCode.COMPANY_LOGO_TOO_HEAVY, "Normalized company logo too heavy");
            }
            return logo;
        } catch (DomainException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
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
    static boolean hasTransparency(BufferedImage image) {
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

    static boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        return (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.COMPANY_LOGO_INVALID, "Unsupported or corrupt company logo");
    }
}

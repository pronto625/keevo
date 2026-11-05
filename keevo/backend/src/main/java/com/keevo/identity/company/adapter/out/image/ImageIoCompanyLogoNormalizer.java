package com.keevo.identity.company.adapter.out.image;

import com.keevo.identity.company.adapter.out.config.CompanyLogoProperties;
import com.keevo.identity.company.domain.port.out.CompanyLogoNormalizer;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.image.ImageReencoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JDK ImageIO logo normalizer (Story 14.2). The format is recognised by its magic bytes (the declared content type
 * is never trusted): a PNG stays a PNG (transparency kept), a JPEG stays a JPEG (quality 0.85); both are decoded and
 * re-encoded — which drops every metadata block — with the longest side capped at
 * {@code keevo.company-logo.max-dimension} (600 px). Anything else → {@code COMPANY_LOGO_INVALID}. The result must
 * stay under {@link #MAX_NORMALIZED_BYTES} (300 KB, inlined in every PDF): an opaque PNG that is heavier is re-encoded
 * as JPEG; still too heavy (or transparent) → {@code COMPANY_LOGO_TOO_HEAVY} (422).
 *
 * <p>Story 19.12 — the re-encoding itself is the shared {@link ImageReencoder} (also used by the store logo);
 * behaviour unchanged.
 */
@Component
public class ImageIoCompanyLogoNormalizer implements CompanyLogoNormalizer {

    /** Largest stored logo after re-encoding: it is inlined in every invoice / export PDF. */
    public static final int MAX_NORMALIZED_BYTES = 300 * 1024;

    private final ImageReencoder reencoder;

    @Autowired
    public ImageIoCompanyLogoNormalizer(CompanyLogoProperties properties) {
        this(properties.getMaxDimension());
    }

    public ImageIoCompanyLogoNormalizer(int maxDimension) {
        this.reencoder = new ImageReencoder(maxDimension);
    }

    @Override
    public Logo normalize(byte[] upload) {
        ImageReencoder.Image image;
        try {
            image = reencoder.reencode(upload, MAX_NORMALIZED_BYTES);
        } catch (ImageReencoder.InvalidImageException e) {
            throw new DomainException(ErrorCode.COMPANY_LOGO_INVALID, "Unsupported or corrupt company logo");
        }
        if (image.content().length > MAX_NORMALIZED_BYTES) {
            throw new DomainException(ErrorCode.COMPANY_LOGO_TOO_HEAVY, "Normalized company logo too heavy");
        }
        return new Logo(image.content(), image.contentType());
    }
}

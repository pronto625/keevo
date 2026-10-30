package com.keevo.identity.company.domain.port.out;

import com.keevo.shared.capability.business.CompanyIdentity.Logo;

/**
 * Checks and re-encodes an uploaded company logo (Story 14.2): PNG or JPEG recognised by its bytes, longest side
 * capped, metadata dropped. Refuses anything else with {@code COMPANY_LOGO_INVALID} (422).
 */
public interface CompanyLogoNormalizer {

    Logo normalize(byte[] upload);
}

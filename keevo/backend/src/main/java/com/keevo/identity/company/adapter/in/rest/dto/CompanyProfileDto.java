package com.keevo.identity.company.adapter.in.rest.dto;

import com.keevo.identity.company.application.service.CompanyProfileService.CompanyProfile;

/**
 * The company identity (Story 14.2): {@code designation} as typed (null when none), {@code displayName} = the name
 * printed on invoices and export documents, {@code shopName} (the tenant name — printed when the designation is
 * blank; null when none was typed), {@code hasLogo}.
 */
public record CompanyProfileDto(String designation, String displayName, String shopName, boolean hasLogo) {

    public static CompanyProfileDto from(CompanyProfile p) {
        return new CompanyProfileDto(p.designation(), p.displayName(), p.shopName(), p.hasLogo());
    }
}

package com.keevo.identity.company.adapter.in.rest.dto;

import com.keevo.identity.company.application.service.CompanyProfileService.FullProfile;

/**
 * {@code GET/PATCH /api/v1/account/company/profile} — the whole identity of the organisation. Every text field is
 * null when not filled in; NIU and RCCM are never mandatory. {@code setupCompleted} is false until the owner has
 * finished the sign-up steps ({@code POST /api/v1/account/company/setup/complete}).
 */
public record CompanyFullProfileDto(String designation, String displayName, String description, String category,
                                    String address, String phone, String email, String niu, String rccm,
                                    String country, boolean hasLogo, boolean setupCompleted) {

    public static CompanyFullProfileDto from(FullProfile p) {
        return new CompanyFullProfileDto(p.designation(), p.displayName(), p.description(), p.category(), p.address(),
                p.phone(), p.email(), p.niu(), p.rccm(), p.country(), p.hasLogo(), p.setupCompleted());
    }
}

package com.keevo.admin.campaign.domain.model;

import java.util.UUID;

/**
 * Story 21.6 — one owner reached by a platform campaign: the account, its company (the oldest when he owns several),
 * the company's schema (push), the names of the template variables, his phone (E.164) and email when known.
 */
public record AudienceMember(UUID userId, UUID tenantId, String tenantSchema, String firstName, String lastName,
                             String companyName, String phone, String email) {
}

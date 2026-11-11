package com.keevo.admin.campaign.domain.model;

import java.util.List;

/**
 * Story 21.6 — the WhatsApp part of a platform campaign (Bref {@code WhatsAppCampaignConfig}): an approved template of
 * the platform number (name, language, its body as approved and its header format), the media of a media header (an
 * uploaded media id or an https link) and one value source per body variable.
 */
public record WhatsAppContent(String templateName, String language, String bodyText, String headerFormat,
                              String headerMediaId, String headerMediaLink, List<Variable> variables) {

    public WhatsAppContent {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }

    /** {@code source}: STATIC, FIRST_NAME, LAST_NAME, FULL_NAME (owner) or COMPANY_NAME; {@code value} fixed / fallback. */
    public record Variable(String source, String value) {

        /** The value for one owner; Meta refuses an empty parameter (fallback, else « - »). */
        public String resolve(String firstName, String lastName, String companyName) {
            String full = ((firstName == null ? "" : firstName.strip()) + " "
                    + (lastName == null ? "" : lastName.strip())).strip();
            String v = switch (source) {
                case "FIRST_NAME" -> firstName;
                case "LAST_NAME" -> lastName;
                case "FULL_NAME" -> full;
                case "COMPANY_NAME" -> companyName;
                default -> value;
            };
            if (v != null && !v.isBlank()) {
                return v.strip().length() > 1024 ? v.strip().substring(0, 1024) : v.strip();
            }
            return value != null && !value.isBlank() ? value.strip() : "-";
        }
    }
}

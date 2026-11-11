package com.keevo.identity.api;

import java.util.List;

/**
 * Story 24.3 — the root category names of an onboarding sector template, for a company that is not onboarded (a Bref
 * owner imported by the migration tool gets the categories an onboarding of its sector would have created).
 */
public interface SectorTemplatePort {

    /**
     * @param sectorCode the onboarding sector code ({@code CLOTHING}, …, {@code OTHER})
     * @return the template's category names in template order; empty for a blank or unknown code
     */
    List<String> templateCategories(String sectorCode);
}

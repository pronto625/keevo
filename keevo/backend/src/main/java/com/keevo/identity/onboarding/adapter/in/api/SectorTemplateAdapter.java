package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.SectorTemplatePort;
import com.keevo.identity.onboarding.application.factory.SectorTemplateFactory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Story 24.3 — implements {@link SectorTemplatePort} with the onboarding's own templates ({@link SectorTemplateFactory}). */
@Component
class SectorTemplateAdapter implements SectorTemplatePort {

    private final SectorTemplateFactory templates;

    SectorTemplateAdapter(SectorTemplateFactory templates) {
        this.templates = templates;
    }

    @Override
    public List<String> templateCategories(String sectorCode) {
        if (sectorCode == null || sectorCode.isBlank()) {
            return List.of();
        }
        SectorType sector;
        try {
            sector = SectorType.valueOf(sectorCode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return List.of();
        }
        return List.copyOf(templates.create(sector).getDefaultCategories());
    }
}

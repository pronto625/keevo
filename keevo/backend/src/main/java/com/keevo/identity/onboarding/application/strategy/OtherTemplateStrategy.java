package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OtherTemplateStrategy — Sector template for ➕ Autre (custom sector).
 * Provides 3 minimal default categories for merchants in unlisted sectors.
 */
@Component
public class OtherTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.OTHER;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Produits Divers",
            "Services",
            "Autres"
        );
    }
}

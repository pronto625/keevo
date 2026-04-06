package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HomeAppliancesTemplateStrategy — Sector template for 🏠 Électroménager & Cuisine.
 * Provides 14 default categories for home appliances merchants.
 */
@Component
public class HomeAppliancesTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.HOME_APPLIANCES;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Électroménager Froid",
            "Électroménager Cuisine",
            "Climatisation & Ventilation",
            "TV & Audio",
            "Literie & Décoration"
        );
    }
}

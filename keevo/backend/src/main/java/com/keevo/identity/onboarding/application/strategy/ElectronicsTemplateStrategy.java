package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ElectronicsTemplateStrategy — Sector template for 📱 Électronique & Smartphones.
 * Provides 14 default categories for electronics merchants.
 */
@Component
public class ElectronicsTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.ELECTRONICS;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Smartphones & Tablettes",
            "Ordinateurs & Périphériques",
            "TV & Écrans",
            "Câbles & Accessoires",
            "Kits Solaires & Batteries"
        );
    }
}

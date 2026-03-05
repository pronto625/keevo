package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ClothingTemplateStrategy — Sector template for 👗 Vêtements & Shopping.
 * Provides 13 default categories for clothing merchants.
 */
@Component
public class ClothingTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.CLOTHING;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Hauts & T-shirts",
            "Pantalons & Jeans",
            "Robes & Jupes",
            "Chaussures Hommes",
            "Chaussures Femmes",
            "Chaussures Enfants",
            "Sacs & Maroquinerie",
            "Accessoires & Bijoux Fantaisie",
            "Sous-vêtements",
            "Vêtements Enfants",
            "Pagnes & Tissus",
            "Vêtements de Sport",
            "Ceintures & Cravates"
        );
    }
}

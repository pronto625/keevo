package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HardwareTemplateStrategy — Sector template for 🔧 Quincaillerie.
 * Provides 14 default categories for hardware store merchants.
 */
@Component
public class HardwareTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.HARDWARE;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Outils",
            "Matériaux Électriques",
            "Plomberie",
            "Peintures & Enduits",
            "Matériaux de Construction"
        );
    }
}

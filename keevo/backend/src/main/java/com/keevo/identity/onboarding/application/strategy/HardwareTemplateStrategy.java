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
            "Outils à Main",
            "Outils Électriques & Perceuses",
            "Visserie & Boulonnerie",
            "Clous & Fixations",
            "Câbles Électriques",
            "Interrupteurs & Prises",
            "Peintures & Vernis",
            "Enduits & Colles",
            "Tuyaux & Raccords Plomberie",
            "Robinetterie",
            "Ciment & Matériaux de Construction",
            "Serrures & Sécurité",
            "Grillages & Barbelés",
            "Batteries & Génératrices"
        );
    }
}

package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PharmacyTemplateStrategy — Sector template for 💊 Pharmacie.
 * Provides 13 default categories for pharmacy merchants.
 */
@Component
public class PharmacyTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.PHARMACY;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Médicaments Génériques",
            "Médicaments de Marque",
            "Vitamines & Suppléments",
            "Contraceptifs",
            "Soins Bébé & Maternité",
            "Soins Visage & Peau",
            "Soins Corps & Hygiène",
            "Produits Capillaires",
            "Parfums & Déodorants",
            "Premiers Secours & Pansements",
            "Matériel Médical",
            "Produits Dentaires",
            "Compléments Alimentaires"
        );
    }
}

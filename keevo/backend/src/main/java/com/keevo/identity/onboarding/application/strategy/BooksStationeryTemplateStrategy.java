package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BooksStationeryTemplateStrategy — Sector template for 📚 Librairie & Fournitures Scolaires.
 * Provides 12 default categories for bookstore/stationery merchants.
 */
@Component
public class BooksStationeryTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.BOOKS_STATIONERY;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Livres & Manuels",
            "Cahiers & Carnets",
            "Stylos & Crayons",
            "Accessoires Scolaires",
            "Fournitures Bureau"
        );
    }
}

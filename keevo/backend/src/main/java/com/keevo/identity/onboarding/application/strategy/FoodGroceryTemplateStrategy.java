package com.keevo.identity.onboarding.application.strategy;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FoodGroceryTemplateStrategy — Sector template for 🍎 Alimentation.
 * Provides 15 default categories for food/grocery merchants (Cameroonian context).
 */
@Component
public class FoodGroceryTemplateStrategy implements SectorTemplateStrategy {

    @Override
    public SectorType getSectorType() {
        return SectorType.FOOD_GROCERY;
    }

    @Override
    public List<String> getDefaultCategories() {
        return List.of(
            "Céréales & Féculents",
            "Fruits & Légumes",
            "Viandes & Poissons",
            "Boissons",
            "Snacks & Produits Ménagers"
        );
    }
}

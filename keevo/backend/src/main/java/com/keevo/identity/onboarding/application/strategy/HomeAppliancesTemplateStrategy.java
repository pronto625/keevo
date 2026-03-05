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
            "Réfrigérateurs & Congélateurs",
            "Climatiseurs & Ventilateurs",
            "Cuisinières à Gaz",
            "Plaques Électriques & Fours",
            "Machines à Laver",
            "Téléviseurs",
            "Mixeurs & Blenders",
            "Fers à Repasser",
            "Bouilloires & Cafetières",
            "Micro-ondes",
            "Ustensiles de Cuisine",
            "Vaisselle & Arts de la Table",
            "Literie & Couvertures",
            "Décoration Maison"
        );
    }
}

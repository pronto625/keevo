package com.keevo.identity.onboarding.domain.model;

/**
 * SectorType — Enumeration of supported business sector templates.
 *
 * <p>Each value maps to a {@link com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy}
 * implementation that provides the default category list for that sector.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
@org.springframework.modulith.NamedInterface("events") // 12.2f: carried by OnboardingCompletedEvent, merged into "identity :: events"
public enum SectorType {

    /** 👗 Vêtements & Shopping */
    CLOTHING,

    /** 📱 Électronique & Smartphones */
    ELECTRONICS,

    /** 📚 Librairie & Fournitures Scolaires */
    BOOKS_STATIONERY,

    /** 🏠 Électroménager & Cuisine */
    HOME_APPLIANCES,

    /** 🍎 Alimentation */
    FOOD_GROCERY,

    /** 💊 Pharmacie */
    PHARMACY,

    /** 🔧 Quincaillerie */
    HARDWARE,

    /** ➕ Autre (custom) */
    OTHER
}

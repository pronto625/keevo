package com.keevo.identity.onboarding.domain.service;

import com.keevo.identity.onboarding.domain.model.SectorType;

import java.util.List;

/**
 * SectorTemplateStrategy — Strategy interface for sector-specific default category lists.
 *
 * <p>GoF Pattern: Strategy — defines the contract for sector template behavior.
 * Each sector (Clothing, Electronics, etc.) has its own {@code @Component} implementation.
 *
 * <p>Open/Closed Principle: adding a new sector = adding a new Strategy implementation
 * + registering in SectorTemplateFactory. Zero modification of existing code.
 *
 * <p>Pure Java — Spring annotations added only in implementing classes.
 */
public interface SectorTemplateStrategy {

    /**
     * @return the SectorType this strategy handles
     */
    SectorType getSectorType();

    /**
     * @return immutable list of default category names for this sector
     */
    List<String> getDefaultCategories();
}

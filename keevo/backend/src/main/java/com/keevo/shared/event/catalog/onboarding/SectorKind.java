package com.keevo.shared.event.catalog.onboarding;

/**
 * SectorKind — business sector carried by {@link OnboardingCompletedEvent} (Story 12.3).
 *
 * <p>Same constants, same names as identity's internal {@code SectorType}; the producer maps by name,
 * so audit details ({@code sectorType}) are unchanged.
 */
public enum SectorKind {
    CLOTHING,
    ELECTRONICS,
    BOOKS_STATIONERY,
    HOME_APPLIANCES,
    FOOD_GROCERY,
    PHARMACY,
    HARDWARE,
    OTHER
}

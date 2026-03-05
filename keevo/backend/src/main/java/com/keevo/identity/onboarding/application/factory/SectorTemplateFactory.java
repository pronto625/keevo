package com.keevo.identity.onboarding.application.factory;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SectorTemplateFactory — Factory that resolves the correct SectorTemplateStrategy
 * for a given SectorType.
 *
 * <p>GoF Pattern: Factory — maps SectorType enum → SectorTemplateStrategy instance.
 * Spring auto-discovers all {@code @Component} implementations of SectorTemplateStrategy
 * via constructor injection of {@code List<SectorTemplateStrategy>}.
 *
 * <p>Open/Closed Principle: adding a new sector = adding a new @Component strategy.
 * This factory does NOT need modification.
 *
 * <p>Fails fast at startup if no strategy is found for a SectorType (caught by tests).
 */
@Component
public class SectorTemplateFactory {

    private final Map<SectorType, SectorTemplateStrategy> strategies;

    /**
     * Spring injects all {@code @Component} beans implementing {@link SectorTemplateStrategy}.
     * The map is built once at startup and is effectively immutable.
     *
     * @param strategyList all registered strategy Spring beans
     */
    public SectorTemplateFactory(List<SectorTemplateStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toMap(SectorTemplateStrategy::getSectorType, s -> s));
    }

    /**
     * Resolve the strategy for the given sector type.
     *
     * @param sectorType the sector to resolve
     * @return the matching strategy
     * @throws IllegalArgumentException if sectorType is null
     * @throws DomainException          if no strategy is registered for this sector
     */
    public SectorTemplateStrategy create(SectorType sectorType) {
        if (sectorType == null) {
            throw new IllegalArgumentException("sectorType must not be null");
        }
        return Optional.ofNullable(strategies.get(sectorType))
            .orElseThrow(() -> new DomainException(
                ErrorCode.SECTOR_TEMPLATE_NOT_FOUND,
                "No template strategy registered for sector: " + sectorType));
    }
}

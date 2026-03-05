package com.keevo.identity.onboarding.domain.port.in;

/**
 * CompleteOnboardingUseCase — Primary port: onboarding completion.
 *
 * <p>Architecture: Primary port (driving side) in hexagonal architecture.
 * Implemented by {@link com.keevo.identity.onboarding.application.service.OnboardingService}.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface CompleteOnboardingUseCase {

    /**
     * Complete the onboarding wizard by setting sector and shop name.
     *
     * @param command validated command containing sector, store name, and actor
     * @return result with tenant info and category count created
     */
    OnboardingResult complete(CompleteOnboardingCommand command);
}

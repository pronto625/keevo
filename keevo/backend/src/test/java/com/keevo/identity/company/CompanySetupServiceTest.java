package com.keevo.identity.company;

import com.keevo.identity.company.application.service.CompanyProfileService;
import com.keevo.identity.company.application.service.CompanySetupService;
import com.keevo.identity.company.domain.model.CompanyCategory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.port.in.SetupSectorOnboardingUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("CompanySetupService — Story 22.19 (D2, D9)")
class CompanySetupServiceTest {

    @Test
    @DisplayName("setup/complete marks the steps and runs the sector onboarding of the category")
    void completeRunsSectorOnboarding() {
        CompanyProfileService profiles = mock(CompanyProfileService.class);
        SetupSectorOnboardingUseCase onboarding = mock(SetupSectorOnboardingUseCase.class);
        UUID owner = UUID.randomUUID();
        var profile = new CompanyProfileService.FullProfile(null, "Épicerie Aicha", null, "food", null, null, null,
                null, null, "CM", false, true);
        when(profiles.completeSetup("kv_s1")).thenReturn(profile);

        assertThat(new CompanySetupService(profiles, onboarding).complete("kv_s1", owner)).isSameAs(profile);

        verify(onboarding).onboardIfAbsent(SectorType.FOOD_GROCERY, "Épicerie Aicha", owner);
    }

    @Test
    @DisplayName("category → sector: the Bref import table, OTHER for the rest or none")
    void sectors() {
        assertThat(CompanyCategory.sector("food")).isEqualTo(SectorType.FOOD_GROCERY);
        assertThat(CompanyCategory.sector("fashion")).isEqualTo(SectorType.CLOTHING);
        assertThat(CompanyCategory.sector("telecom")).isEqualTo(SectorType.ELECTRONICS);
        assertThat(CompanyCategory.sector("automotive")).isEqualTo(SectorType.HARDWARE);
        assertThat(CompanyCategory.sector("beauty")).isEqualTo(SectorType.OTHER);
        assertThat(CompanyCategory.sector(null)).isEqualTo(SectorType.OTHER);
    }

    @Test
    @DisplayName("the accepted categories are listed by their French labels, never raw keys")
    void acceptedInFrench() {
        assertThat(CompanyCategory.accepted()).contains("Immobilier", "Alimentation", "Mode et vêtements")
                .doesNotContain("real_estate");
    }

    @Test
    @DisplayName("a French label from the message is accepted too (case and accents ignored)")
    void labelsAccepted() {
        assertThat(CompanyCategory.resolve("Immobilier")).contains("real_estate");
        assertThat(CompanyCategory.resolve("beaute et cosmetiques")).contains("beauty");
        assertThat(CompanyCategory.resolve(" food ")).contains("food");
        assertThat(CompanyCategory.resolve("Boulangerie")).isEmpty();
    }
}

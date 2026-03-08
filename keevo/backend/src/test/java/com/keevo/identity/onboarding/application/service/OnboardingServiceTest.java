package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.application.factory.SectorTemplateFactory;
import com.keevo.identity.onboarding.application.strategy.ClothingTemplateStrategy;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.identity.onboarding.domain.port.out.OnboardingStoreRepository;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingService")
class OnboardingServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock TenantPreferencesRepository tenantPreferencesRepository;
    @Mock OnboardingStoreRepository storeRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SectorTemplateFactory sectorTemplateFactory;

    @InjectMocks OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        // Default: onboarding not yet completed (allows happy-path tests to proceed)
        when(tenantPreferencesRepository.hasOnboardingCompleted()).thenReturn(false);
    }

    @Test
    @DisplayName("should throw ONBOARDING_ALREADY_COMPLETED when tenant has already onboarded")
    void should_throw_when_onboarding_already_completed() {
        when(tenantPreferencesRepository.hasOnboardingCompleted()).thenReturn(true);

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");
            var command = new CompleteOnboardingCommand(SectorType.CLOTHING, "Test", UUID.randomUUID());

            assertThatThrownBy(() -> onboardingService.complete(command))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("already completed");

            verify(categoryRepository, never()).saveAll(any());
            verify(storeRepository, never()).updateStoreName(any());
        }
    }
    void should_create_correct_number_of_categories_for_CLOTHING() {
        SectorTemplateStrategy clothingStrategy = new ClothingTemplateStrategy();
        when(sectorTemplateFactory.create(SectorType.CLOTHING)).thenReturn(clothingStrategy);
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantPreferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");

            var command = new CompleteOnboardingCommand(SectorType.CLOTHING, "Boutique Céleste", UUID.randomUUID());

            OnboardingResult result = onboardingService.complete(command);

            verify(categoryRepository).saveAll(argThat(cats ->
                ((List<?>) cats).size() == 13));
            assertThat(result.categoriesCreated()).isEqualTo(13);
            assertThat(result.storeName()).isEqualTo("Boutique Céleste");
        }
    }

    @Test
    @DisplayName("should update store name")
    void should_update_store_name() {
        when(sectorTemplateFactory.create(any())).thenReturn(new ClothingTemplateStrategy());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantPreferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");
            var command = new CompleteOnboardingCommand(SectorType.CLOTHING, "Ma Boutique Chic", UUID.randomUUID());

            onboardingService.complete(command);

            verify(storeRepository).updateStoreName("Ma Boutique Chic");
        }
    }

    @Test
    @DisplayName("should save tenant preferences with defaults")
    void should_save_tenant_preferences_with_defaults() {
        when(sectorTemplateFactory.create(any())).thenReturn(new ClothingTemplateStrategy());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantPreferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");
            var command = new CompleteOnboardingCommand(SectorType.CLOTHING, "Test", UUID.randomUUID());

            onboardingService.complete(command);

            verify(tenantPreferencesRepository).save(argThat(prefs ->
                prefs.sectorType() == SectorType.CLOTHING
                    && prefs.eodReportTime().equals("20:00:00")
                    && prefs.stockAlertEnabled()));
        }
    }

    @Test
    @DisplayName("should publish OnboardingCompletedEvent")
    void should_publish_OnboardingCompletedEvent() {
        when(sectorTemplateFactory.create(any())).thenReturn(new ClothingTemplateStrategy());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantPreferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");
            var command = new CompleteOnboardingCommand(SectorType.FOOD_GROCERY, "Mon Épicerie", UUID.randomUUID());

            onboardingService.complete(command);

            verify(eventPublisher).publishEvent((Object) argThat(evt ->
                evt instanceof OnboardingCompletedEvent e
                    && e.sectorType() == SectorType.FOOD_GROCERY
                    && e.storeName().equals("Mon Épicerie")));
        }
    }

    @Test
    @DisplayName("categories should have distinct UUIDs, isActive=true, and non-null createdAt")
    void categories_should_have_valid_fields() {
        when(sectorTemplateFactory.create(any())).thenReturn(new ClothingTemplateStrategy());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantPreferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_abc123");
            var command = new CompleteOnboardingCommand(SectorType.CLOTHING, "Boutique Test", UUID.randomUUID());

            onboardingService.complete(command);

            verify(categoryRepository).saveAll(argThat(cats -> {
                @SuppressWarnings("unchecked")
                List<Category> categories = (List<Category>) cats;
                long distinctIds = categories.stream().map(Category::id).distinct().count();
                boolean allActive = categories.stream().allMatch(Category::isActive);
                boolean allNonNullCreatedAt = categories.stream().allMatch(c -> c.createdAt() != null);
                return distinctIds == categories.size() && allActive && allNonNullCreatedAt;
            }));
        }
    }
}

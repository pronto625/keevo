package com.keevo.identity.onboarding.application.factory;

import com.keevo.identity.onboarding.application.strategy.ClothingTemplateStrategy;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@DisplayName("SectorTemplateFactory")
class SectorTemplateFactoryTest {

    @Autowired
    SectorTemplateFactory factory;

    @Test
    @DisplayName("should return clothing strategy for CLOTHING")
    void should_return_clothing_strategy_for_CLOTHING() {
        SectorTemplateStrategy strategy = factory.create(SectorType.CLOTHING);
        assertThat(strategy).isInstanceOf(ClothingTemplateStrategy.class);
    }

    @Test
    @DisplayName("should return correct category count for each sector")
    void should_return_correct_category_count_for_each_sector() {
        assertThat(factory.create(SectorType.CLOTHING).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.ELECTRONICS).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.BOOKS_STATIONERY).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.HOME_APPLIANCES).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.FOOD_GROCERY).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.PHARMACY).getDefaultCategories()).hasSize(4);
        assertThat(factory.create(SectorType.HARDWARE).getDefaultCategories()).hasSize(5);
        assertThat(factory.create(SectorType.OTHER).getDefaultCategories()).hasSize(3);
    }

    @Test
    @DisplayName("should have strategy for every SectorType enum value")
    void should_have_strategy_for_every_SectorType_enum_value() {
        Arrays.stream(SectorType.values()).forEach(sector ->
            assertThatNoException().isThrownBy(() -> factory.create(sector)));
    }

    @Test
    @DisplayName("should throw DomainException for null sector")
    void should_throw_DomainException_for_null_sector() {
        assertThatThrownBy(() -> factory.create(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("category lists should be immutable")
    void category_lists_should_be_immutable() {
        Arrays.stream(SectorType.values()).forEach(sector -> {
            var categories = factory.create(sector).getDefaultCategories();
            assertThatThrownBy(() -> categories.add("INTRUDER"))
                .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    @DisplayName("no duplicate categories within a single sector")
    void no_duplicate_categories_within_same_sector() {
        Arrays.stream(SectorType.values()).forEach(sector -> {
            var categories = factory.create(sector).getDefaultCategories();
            assertThat(categories).doesNotHaveDuplicates();
        });
    }
}

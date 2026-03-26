package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.service.InventoryScopeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ScopeResolverRegistry — scope → resolver mapping")
class ScopeResolverRegistryTest {

    @Test
    void get_shouldReturnResolverForRegisteredScope() {
        InventoryScopeResolver fullResolver = mock(InventoryScopeResolver.class);
        when(fullResolver.supportedScope()).thenReturn(InventoryScope.FULL);

        var registry = new ScopeResolverRegistry(List.of(fullResolver));

        assertThat(registry.get(InventoryScope.FULL)).isSameAs(fullResolver);
    }

    @Test
    void get_shouldThrowForUnregisteredScope() {
        var registry = new ScopeResolverRegistry(List.of());

        assertThatThrownBy(() -> registry.get(InventoryScope.FULL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FULL");
    }

    @Test
    void shouldRegisterMultipleResolvers() {
        InventoryScopeResolver fullResolver = mock(InventoryScopeResolver.class);
        when(fullResolver.supportedScope()).thenReturn(InventoryScope.FULL);
        InventoryScopeResolver partialResolver = mock(InventoryScopeResolver.class);
        when(partialResolver.supportedScope()).thenReturn(InventoryScope.PARTIAL);

        var registry = new ScopeResolverRegistry(List.of(fullResolver, partialResolver));

        assertThat(registry.get(InventoryScope.FULL)).isSameAs(fullResolver);
        assertThat(registry.get(InventoryScope.PARTIAL)).isSameAs(partialResolver);
    }
}

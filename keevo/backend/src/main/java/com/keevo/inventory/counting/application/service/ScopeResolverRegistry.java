package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.service.InventoryScopeResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ScopeResolverRegistry — GoF Factory/Registry mapping InventoryScope → InventoryScopeResolver.
 *
 * <p>Open/Closed: new scope = implement InventoryScopeResolver + register via Spring DI.
 */
@Component
public class ScopeResolverRegistry {

    private final Map<InventoryScope, InventoryScopeResolver> resolvers;

    public ScopeResolverRegistry(List<InventoryScopeResolver> allResolvers) {
        this.resolvers = allResolvers.stream()
                .collect(Collectors.toMap(InventoryScopeResolver::supportedScope, Function.identity()));
    }

    public InventoryScopeResolver get(InventoryScope scope) {
        return Optional.ofNullable(resolvers.get(scope))
                .orElseThrow(() -> new IllegalStateException("No resolver for scope: " + scope));
    }
}

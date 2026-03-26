package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.GetCountingProductsQuery;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.inventory.counting.domain.service.InventoryScopeResolver;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCountingProductsService — load products in scope")
class GetCountingProductsServiceTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private InventoryCountRepository countRepository;
    @Mock private ScopeResolverRegistry resolverRegistry;
    @Mock private InventoryScopeResolver scopeResolver;

    private GetCountingProductsService service;

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GetCountingProductsService(sessionRepository, countRepository, resolverRegistry);
    }

    @Test
    void execute_shouldThrowWhenSessionNotFound() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new GetCountingProductsQuery(SESSION_ID)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_FOUND);
    }

    @Test
    void execute_shouldDelegateToScopeResolver() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, UUID.randomUUID());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(countRepository.findBySessionId(session.getId())).thenReturn(List.of());
        when(resolverRegistry.get(InventoryScope.FULL)).thenReturn(scopeResolver);

        var expectedRow = InventoryProductRow.of(
                UUID.randomUUID(), "Produit", null, null, null, null, 10, null);
        when(scopeResolver.resolveProducts(eq(STORE_ID), any(), any())).thenReturn(List.of(expectedRow));

        List<InventoryProductRow> result = service.execute(new GetCountingProductsQuery(SESSION_ID));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).productName()).isEqualTo("Produit");
        verify(resolverRegistry).get(InventoryScope.FULL);
    }

    @Test
    void execute_shouldPassExistingCountsToResolver() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, UUID.randomUUID());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        var existingCount = InventoryCount.create(
                session.getId(), UUID.randomUUID(), null, "P", null, 10, 8, UUID.randomUUID());
        when(countRepository.findBySessionId(session.getId())).thenReturn(List.of(existingCount));
        when(resolverRegistry.get(InventoryScope.FULL)).thenReturn(scopeResolver);
        when(scopeResolver.resolveProducts(eq(STORE_ID), any(), eq(List.of(existingCount)))).thenReturn(List.of());

        service.execute(new GetCountingProductsQuery(SESSION_ID));

        verify(scopeResolver).resolveProducts(eq(STORE_ID), any(), eq(List.of(existingCount)));
    }
}

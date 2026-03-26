package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.GetCountingProductsQuery;
import com.keevo.inventory.counting.domain.port.in.GetCountingProductsUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GetCountingProductsService — loads all products in scope for a counting session.
 */
@Service
@Transactional(readOnly = true)
public class GetCountingProductsService implements GetCountingProductsUseCase {

    private final InventorySessionRepository sessionRepository;
    private final InventoryCountRepository countRepository;
    private final ScopeResolverRegistry resolverRegistry;

    public GetCountingProductsService(InventorySessionRepository sessionRepository,
                                      InventoryCountRepository countRepository,
                                      ScopeResolverRegistry resolverRegistry) {
        this.sessionRepository = sessionRepository;
        this.countRepository = countRepository;
        this.resolverRegistry = resolverRegistry;
    }

    @Override
    public List<InventoryProductRow> execute(GetCountingProductsQuery query) {
        InventorySession session = sessionRepository.findById(query.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session d'inventaire introuvable : " + query.sessionId()));

        List<InventoryCount> existingCounts = countRepository.findBySessionId(session.getId());

        return resolverRegistry.get(session.getScope())
                .resolveProducts(session.getStoreId(), session.getCategoryIds(), existingCounts);
    }
}

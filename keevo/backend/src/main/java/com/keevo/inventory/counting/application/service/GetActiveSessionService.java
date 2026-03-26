package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.GetActiveSessionQuery;
import com.keevo.inventory.counting.domain.port.in.GetActiveSessionUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class GetActiveSessionService implements GetActiveSessionUseCase {

    private final InventorySessionRepository sessionRepository;

    public GetActiveSessionService(InventorySessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public Optional<InventorySession> execute(GetActiveSessionQuery query) {
        return sessionRepository.findActiveByStoreId(query.storeId());
    }
}

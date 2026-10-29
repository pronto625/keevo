package com.keevo.inventory.counting.adapter.in.api;

import com.keevo.inventory.api.CountingQueryPort;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published {@link CountingQueryPort} on the session repository (Story 12.2b).
 * No transaction of its own: it joins the caller's.
 */
@Component
class CountingQueryAdapter implements CountingQueryPort {

    private final InventorySessionRepository sessionRepository;

    CountingQueryAdapter(InventorySessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public Optional<UUID> findSessionStoreId(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(InventorySession::getStoreId);
    }

    @Override
    public boolean isSessionValidated(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(s -> s.getStatus() == InventorySessionStatus.VALIDATED)
                .orElse(false);
    }
}

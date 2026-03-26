package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.ListInventorySessionsQuery;
import com.keevo.inventory.counting.domain.port.in.ListInventorySessionsUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ListInventorySessionsService implements ListInventorySessionsUseCase {

    private final InventorySessionRepository sessionRepository;

    public ListInventorySessionsService(InventorySessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public List<InventorySession> execute(ListInventorySessionsQuery query) {
        if (query.storeId() != null) {
            return sessionRepository.findByStoreId(query.storeId(), query.page(), query.size());
        }
        return sessionRepository.findAll(query.page(), query.size());
    }
}

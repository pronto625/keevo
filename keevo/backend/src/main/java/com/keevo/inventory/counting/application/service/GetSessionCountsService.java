package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.port.in.GetSessionCountsQuery;
import com.keevo.inventory.counting.domain.port.in.GetSessionCountsUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GetSessionCountsService — returns all saved counts for an inventory session.
 */
@Service
@Transactional(readOnly = true)
public class GetSessionCountsService implements GetSessionCountsUseCase {

    private final InventoryCountRepository countRepository;

    public GetSessionCountsService(InventoryCountRepository countRepository) {
        this.countRepository = countRepository;
    }

    @Override
    public List<InventoryCount> execute(GetSessionCountsQuery query) {
        return countRepository.findBySessionId(query.sessionId());
    }
}

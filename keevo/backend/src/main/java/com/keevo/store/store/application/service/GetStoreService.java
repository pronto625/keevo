package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.GetStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Story 22.19 (D12) — see {@link GetStoreUseCase}: one primary-key read, the store rule checked first. */
@Service
public class GetStoreService implements GetStoreUseCase {

    private final StoreRepository stores;

    public GetStoreService(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    @Transactional(readOnly = true)
    public Store execute(UUID storeId, UUID assignedStoreId) {
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, "Vous n'avez accès qu'à votre boutique");
        }
        return stores.findById(storeId)
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }
}

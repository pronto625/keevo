package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.CloseDayInput;
import com.keevo.commerce.api.DayClosureCommandPort;
import com.keevo.commerce.api.DayClosureQueryPort;
import com.keevo.commerce.api.DayClosureView;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published day-closure ports (Story 12.2c) by delegating to {@link CloseDayUseCase}
 * (its own transaction) and the {@link DayClosureRepository} port. No transaction of its own.
 */
@Component
class DayClosureApiAdapter implements DayClosureCommandPort, DayClosureQueryPort {

    private final CloseDayUseCase closeDayUseCase;
    private final DayClosureRepository dayClosureRepository;

    DayClosureApiAdapter(CloseDayUseCase closeDayUseCase, DayClosureRepository dayClosureRepository) {
        this.closeDayUseCase = closeDayUseCase;
        this.dayClosureRepository = dayClosureRepository;
    }

    @Override
    public DayClosureView closeDay(String tenantSchema, CloseDayInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return closeDayUseCase.closeDay(new CloseDayCommand(
                in.storeId(), in.actorId(), tenantSchema, in.automatic(), in.closureId(), in.closedAt()))
                .toView();
    }

    @Override
    public Optional<DayClosureView> findById(String tenantSchema, UUID closureId) {
        TenantContext.requireCurrent(tenantSchema);
        return dayClosureRepository.findById(closureId).map(DayClosure::toView);
    }

    @Override
    public List<DayClosureView> findByStoreAndDate(String tenantSchema, UUID storeId, LocalDate date) {
        TenantContext.requireCurrent(tenantSchema);
        return dayClosureRepository.findByStoreIdAndDate(storeId, date).stream().map(DayClosure::toView).toList();
    }

    @Override
    public Optional<DayClosureView> findPreviousForStore(String tenantSchema, UUID storeId, Instant before) {
        TenantContext.requireCurrent(tenantSchema);
        return dayClosureRepository.findPreviousForStore(storeId, before).map(DayClosure::toView);
    }
}

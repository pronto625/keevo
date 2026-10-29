package com.keevo.inventory.counting.adapter.in.api;

import com.keevo.inventory.api.CountingCommandPort;
import com.keevo.inventory.api.OpenCountingSessionInput;
import com.keevo.inventory.api.SaveCountInput;
import com.keevo.inventory.api.ValidateCountingInput;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionUseCase;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link CountingCommandPort} by delegating to the existing counting
 * input ports (Story 12.2b). No transaction of its own.
 */
@Component
class CountingCommandAdapter implements CountingCommandPort {

    private final CreateInventorySessionUseCase createSession;
    private final SaveInventoryCountUseCase saveCount;
    private final ValidateInventoryUseCase validateInventory;

    CountingCommandAdapter(CreateInventorySessionUseCase createSession,
                                  SaveInventoryCountUseCase saveCount,
                                  ValidateInventoryUseCase validateInventory) {
        this.createSession = createSession;
        this.saveCount = saveCount;
        this.validateInventory = validateInventory;
    }

    @Override
    public UUID openSession(OpenCountingSessionInput in) {
        return createSession.execute(new CreateInventorySessionCommand(
                in.sessionId(), in.storeId(), InventoryScope.valueOf(in.scope().name()),
                in.categoryIds(), in.actorId())).getId();
    }

    @Override
    public UUID saveCount(SaveCountInput in) {
        return saveCount.execute(new SaveInventoryCountCommand(
                in.sessionId(), in.productId(), in.variantId(),
                in.productName(), in.variantLabel(),
                in.theoretical(), in.physical(), in.actorId(), in.countId())).getId();
    }

    @Override
    public UUID validate(ValidateCountingInput in) {
        return validateInventory.execute(
                new ValidateInventoryCommand(in.sessionId(), in.actorId(), in.movementIds())).sessionId();
    }
}

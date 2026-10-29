package com.keevo.inventory.counting.adapter.in.api;

import com.keevo.inventory.api.CountingScope;
import com.keevo.inventory.api.OpenCountingSessionInput;
import com.keevo.inventory.api.SaveCountInput;
import com.keevo.inventory.api.ValidateCountingInput;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.model.ValidateInventoryResult;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionUseCase;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2b — the counting command port maps API inputs onto the existing counting ports. */
@ExtendWith(MockitoExtension.class)
class CountingCommandAdapterTest {

    @Mock private CreateInventorySessionUseCase createSession;
    @Mock private SaveInventoryCountUseCase saveCount;
    @Mock private ValidateInventoryUseCase validateInventory;

    private CountingCommandAdapter adapter;

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new CountingCommandAdapter(createSession, saveCount, validateInventory);
    }

    @ParameterizedTest
    @EnumSource(CountingScope.class)
    void openSession_mapsTheScopeByNameAndReturnsTheSessionId(CountingScope scope) {
        List<UUID> categories = scope == CountingScope.PARTIAL ? List.of(UUID.randomUUID()) : null;
        InventoryScope internal = InventoryScope.valueOf(scope.name());
        when(createSession.execute(any())).thenReturn(
                InventorySession.create(SESSION, STORE, internal, categories, ACTOR));

        UUID id = adapter.openSession(new OpenCountingSessionInput(SESSION, STORE, scope, categories, ACTOR));

        assertThat(id).isEqualTo(SESSION);
        verify(createSession).execute(new CreateInventorySessionCommand(SESSION, STORE, internal, categories, ACTOR));
    }

    @Test
    void saveCount_mapsEveryFieldAndReturnsTheCountId() {
        UUID product = UUID.randomUUID();
        UUID countId = UUID.randomUUID();
        when(saveCount.execute(any())).thenReturn(
                InventoryCount.create(countId, SESSION, product, null, "Savon", null, 50, 47, ACTOR));

        UUID id = adapter.saveCount(new SaveCountInput(SESSION, product, null, "Savon", null, 50, 47, ACTOR, countId));

        assertThat(id).isEqualTo(countId);
        verify(saveCount).execute(new SaveInventoryCountCommand(
                SESSION, product, null, "Savon", null, 50, 47, ACTOR, countId));
    }

    @Test
    void validate_passesTheDeviceMovementIdsAndReturnsTheSessionId() {
        Map<String, UUID> movementIds = Map.of(UUID.randomUUID() + "|", UUID.randomUUID());
        when(validateInventory.execute(any())).thenReturn(
                new ValidateInventoryResult(SESSION, 1, InventorySessionStatus.VALIDATED, Instant.now()));

        UUID id = adapter.validate(new ValidateCountingInput(SESSION, ACTOR, movementIds));

        assertThat(id).isEqualTo(SESSION);
        verify(validateInventory).execute(new ValidateInventoryCommand(SESSION, ACTOR, movementIds));
    }

    @Test
    void validateCountingInput_nullMovementIds_isEmpty() {
        assertThat(new ValidateCountingInput(SESSION, ACTOR, null).movementIds()).isEmpty();
    }

    @Test
    void openCountingSessionInput_nullScope_isAValidationError() {
        assertThatThrownBy(() -> new OpenCountingSessionInput(SESSION, STORE, null, null, ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.name()));
        verifyNoInteractions(createSession);
    }
}

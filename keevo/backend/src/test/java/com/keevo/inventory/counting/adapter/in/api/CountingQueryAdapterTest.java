package com.keevo.inventory.counting.adapter.in.api;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Story 12.2b — the counting query port exposes a session's store and validated state. */
@ExtendWith(MockitoExtension.class)
class CountingQueryAdapterTest {

    @Mock private InventorySessionRepository sessionRepository;

    private CountingQueryAdapter adapter;

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new CountingQueryAdapter(sessionRepository);
    }

    private static InventorySession session(InventorySessionStatus status) {
        return new InventorySession(SESSION, STORE, InventoryScope.FULL, null, status,
                UUID.randomUUID(), Instant.now(), null, null, null, Instant.now());
    }

    @Test
    void findSessionStoreId_existingSession() {
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.of(session(InventorySessionStatus.IN_PROGRESS)));

        assertThat(adapter.findSessionStoreId(SESSION)).contains(STORE);
    }

    @Test
    void findSessionStoreId_unknownSession_isEmpty() {
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.empty());

        assertThat(adapter.findSessionStoreId(SESSION)).isEmpty();
    }

    @Test
    void isSessionValidated_onlyForAValidatedSession() {
        when(sessionRepository.findById(SESSION))
                .thenReturn(Optional.of(session(InventorySessionStatus.VALIDATED)))
                .thenReturn(Optional.of(session(InventorySessionStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());

        assertThat(adapter.isSessionValidated(SESSION)).isTrue();
        assertThat(adapter.isSessionValidated(SESSION)).isFalse();
        assertThat(adapter.isSessionValidated(SESSION)).isFalse();
    }
}

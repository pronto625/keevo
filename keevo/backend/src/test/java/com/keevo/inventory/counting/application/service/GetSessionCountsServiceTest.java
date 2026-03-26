package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.port.in.GetSessionCountsQuery;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetSessionCountsService — retrieve all counts for a session")
class GetSessionCountsServiceTest {

    @Mock private InventoryCountRepository countRepository;
    @InjectMocks private GetSessionCountsService service;

    @Test
    void execute_shouldDelegateToRepository() {
        UUID sessionId = UUID.randomUUID();
        var count = InventoryCount.create(
                sessionId, UUID.randomUUID(), null, "P", null, 10, 8, UUID.randomUUID());
        when(countRepository.findBySessionId(sessionId)).thenReturn(List.of(count));

        List<InventoryCount> result = service.execute(new GetSessionCountsQuery(sessionId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductName()).isEqualTo("P");
    }

    @Test
    void execute_shouldReturnEmptyWhenNoCounts() {
        UUID sessionId = UUID.randomUUID();
        when(countRepository.findBySessionId(sessionId)).thenReturn(List.of());

        List<InventoryCount> result = service.execute(new GetSessionCountsQuery(sessionId));

        assertThat(result).isEmpty();
    }
}

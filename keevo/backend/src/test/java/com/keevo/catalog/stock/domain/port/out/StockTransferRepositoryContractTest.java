package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockTransferRepositoryContractTest — contract test verifying the port interface exists.
 * Task 4.1 — Story 3.3. RED first.
 */
class StockTransferRepositoryContractTest {

    @Test
    void repository_shouldDefineSave() throws NoSuchMethodException {
        Method saveMethod = StockTransferRepository.class.getMethod("save", StockTransfer.class);
        assertThat(saveMethod.getReturnType()).isEqualTo(StockTransfer.class);
    }

    @Test
    void repository_shouldDefineFindByFilters() throws NoSuchMethodException {
        Method method = StockTransferRepository.class.getMethod(
            "findByFilters",
            UUID.class, UUID.class, Instant.class, Instant.class,
            org.springframework.data.domain.Pageable.class
        );
        assertThat(method.getReturnType()).isEqualTo(Page.class);
    }
}

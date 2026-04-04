package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiStoreStockRepositoryContractTest — documents required methods for MultiStoreStockRepository.
 * Story 3.2. TDD RED phase.
 */
class MultiStoreStockRepositoryContractTest {

    @Test
    void repository_shouldDefineGetStoreOverviews() throws NoSuchMethodException {
        Method method = MultiStoreStockRepository.class.getMethod("getStoreOverviews");
        assertThat(method.getReturnType()).isEqualTo(List.class);
    }

    @Test
    void repository_shouldDefineGetStoreStockDetail() throws NoSuchMethodException {
        Method method = MultiStoreStockRepository.class.getMethod(
            "getStoreStockDetail", UUID.class, boolean.class, boolean.class,
            org.springframework.data.domain.Pageable.class);
        assertThat(method.getReturnType()).isEqualTo(Page.class);
    }
}

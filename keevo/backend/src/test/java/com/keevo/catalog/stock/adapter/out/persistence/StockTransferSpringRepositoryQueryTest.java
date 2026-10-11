package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pull finds transfers by updated_at: a status transition (IN_TRANSIT → COMPLETED) that
 * left it untouched never reached the other devices. Regression guard on the query itself
 * (persistence tests need a database this suite does not start).
 */
class StockTransferSpringRepositoryQueryTest {

    @Test
    void transitionStatus_bumpsUpdatedAtAndVersion() throws Exception {
        var transition = Arrays.stream(StockTransferSpringRepository.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Query.class))
                .filter(m -> m.getName().startsWith("transitionStatus"))
                .findFirst()
                .orElseThrow();

        String sql = transition.getAnnotation(Query.class).value();

        assertThat(sql).contains("updated_at = now()");
        assertThat(sql).contains("version = version + 1");
        assertThat(sql).contains("WHERE id = :id AND status = :expectedStatus");
    }

    @Test
    void transitionStatus_keepsItsEnumSignature() throws Exception {
        assertThat(StockTransferSpringRepository.class.getMethod(
                "transitionStatus", UUID.class, TransferStatus.class, TransferStatus.class)).isNotNull();
    }
}

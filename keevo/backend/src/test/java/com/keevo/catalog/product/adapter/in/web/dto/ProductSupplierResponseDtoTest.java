package com.keevo.catalog.product.adapter.in.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.crm.contact.adapter.in.api.CrmContactApiTestSupport;
import com.keevo.crm.contact.adapter.in.web.dto.SupplierResponseDto;
import com.keevo.crm.contact.domain.entity.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.2a/12.2b — the body of {@code GET /products/{id}/supplier} is now built from the crm
 * public API ({@code ContactViews.of} → {@link ProductSupplierResponseDto}); serialized with the
 * application's {@link ObjectMapper}, it must stay identical to the crm supplier payload it replaces.
 */
@JsonTest
class ProductSupplierResponseDtoTest {

    @Autowired private ObjectMapper mapper;

    private static final Instant CREATED = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-01T08:00:00Z");

    private static Supplier supplier() {
        return new Supplier(UUID.randomUUID(), "Distributeur SA", "+237690000099", null,
                false, CREATED, UPDATED);
    }

    private void assertSameJson(Supplier supplier, List<UUID> productIds) throws Exception {
        String expected = mapper.writeValueAsString(SupplierResponseDto.fromDomain(supplier, productIds));
        String actual = mapper.writeValueAsString(
                ProductSupplierResponseDto.from(CrmContactApiTestSupport.supplierView(supplier, productIds)));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void json_isIdenticalToTheCrmSupplierPayload() throws Exception {
        assertSameJson(supplier(), List.of(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void json_nullProductIds_staysNull() throws Exception {
        Supplier supplier = supplier();
        assertSameJson(supplier, null);
        assertThat(mapper.writeValueAsString(
                ProductSupplierResponseDto.from(CrmContactApiTestSupport.supplierView(supplier, null))))
                .contains("\"productIds\":null");
    }
}

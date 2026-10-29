package com.keevo.inventory.stock.adapter.in.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.catalog.product.adapter.in.api.CatalogProductApiTestSupport;
import com.keevo.catalog.product.adapter.in.web.dto.ProductResponseDto;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.2a — {@code PATCH /products/{id}/threshold} now answers with a DTO built from the
 * catalog public API; its JSON must stay identical to the catalog product payload it replaces.
 */
class ProductThresholdResponseDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void json_isIdenticalToTheCatalogProductPayload() throws Exception {
        for (ProductStatus status : ProductStatus.values()) {
            Product product = new Product(UUID.randomUUID(), "Robe Wax", null, "KEV-ROBE01", UUID.randomUUID(),
                    5000, 3000, 150, 12, true, status, 4,
                    Instant.parse("2026-01-01T08:00:00Z"), Instant.parse("2026-02-01T08:00:00Z"));

            String expected = mapper.writeValueAsString(ProductResponseDto.fromDomain(product));
            String actual = mapper.writeValueAsString(ProductThresholdResponseDto.from(CatalogProductApiTestSupport.view(product)));

            assertThat(actual).isEqualTo(expected);
        }
    }
}

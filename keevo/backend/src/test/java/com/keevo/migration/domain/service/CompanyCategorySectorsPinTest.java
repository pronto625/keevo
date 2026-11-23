package com.keevo.migration.domain.service;

import com.keevo.identity.company.domain.model.CompanyCategory;
import com.keevo.store.api.StoreCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 22.19 (D2) — the sign-up sector of a category is the Bref import's, for every category. */
@DisplayName("CompanyCategory.sector pinned against BrefMappings.sector")
class CompanyCategorySectorsPinTest {

    @Test
    void sameSectorForEveryCategory() {
        for (StoreCategory c : StoreCategory.values()) {
            assertThat(CompanyCategory.sector(c.key()).name()).as(c.key()).isEqualTo(BrefMappings.sector(c.key()));
        }
    }
}

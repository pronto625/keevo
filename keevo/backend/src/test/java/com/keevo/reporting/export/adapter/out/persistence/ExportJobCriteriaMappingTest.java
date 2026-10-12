package com.keevo.reporting.export.adapter.out.persistence;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.shared.application.port.export.ExportFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Export job criteria persistence mapping (v1s-18-2)")
class ExportJobCriteriaMappingTest {

    private static ExportJob job(ExportFilter filter) {
        return ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.XLSX, List.of("audit_log"),
                filter, "f.xlsx", "x", Instant.parse("2026-10-12T12:30:00Z"));
    }

    @Test
    @DisplayName("criteria → sorted JSON text → same map; no criteria → NULL column")
    void roundTrip() {
        UUID store = UUID.randomUUID();
        ExportFilter filter = new ExportFilter(store, Instant.parse("2026-10-01T00:00:00Z"), null,
                Map.of("q", "Vente \"spéciale\"", "action", "SALE_CREATED"));

        ExportJobJpaEntity entity = ExportJobRepositoryAdapter.toEntity(job(filter));
        assertThat(entity.getCriteria()).isEqualTo("{\"action\":\"SALE_CREATED\",\"q\":\"Vente \\\"spéciale\\\"\"}");
        assertThat(ExportJobRepositoryAdapter.toDomain(entity).getFilter()).isEqualTo(filter);

        ExportJobJpaEntity none = ExportJobRepositoryAdapter.toEntity(job(ExportFilter.none()));
        assertThat(none.getCriteria()).isNull();
        assertThat(ExportJobRepositoryAdapter.toDomain(none).getFilter().criteria()).isEmpty();
    }

    @Test
    @DisplayName("corrupt / non-object criteria column → no criteria, never an exception (listing keeps working)")
    void corruptCriteria() {
        for (String bad : List.of("{not json", "[\"a\"]", "42", "null", "{\"a\":{\"b\":1}}")) {
            assertThat(ExportJobRepositoryAdapter.criteria(bad)).as(bad).isEmpty();
        }
    }
}

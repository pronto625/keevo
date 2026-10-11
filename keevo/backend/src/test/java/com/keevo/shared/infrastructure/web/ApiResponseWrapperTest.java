package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResponseWrapperTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void paginated_buildsMetaWithPageSizeTotalElementsTotalPages() {
        var wrapper = ApiResponseWrapper.paginated("test-data", 2, 25, 150L, 6);

        assertThat(wrapper.data()).isEqualTo("test-data");
        assertThat(wrapper.error()).isNull();
        assertThat(wrapper.code()).isNull();
        assertThat(wrapper.domainCode()).isNull();
        assertThat(wrapper.details()).isNull();
        assertThat(wrapper.meta()).isNotNull();

        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) wrapper.meta();
        assertThat(meta).containsEntry("page", 2);
        assertThat(meta).containsEntry("size", 25);
        assertThat(meta).containsEntry("totalElements", 150L);
        assertThat(meta).containsEntry("totalPages", 6);
    }

    @Test
    void ok_hasNullMeta() {
        var wrapper = ApiResponseWrapper.ok("data");

        assertThat(wrapper.data()).isEqualTo("data");
        assertThat(wrapper.meta()).isNull();
    }

    @Test
    void ok_jsonOmitsMeta() throws Exception {
        var wrapper = ApiResponseWrapper.ok("data");
        var json = mapper.writeValueAsString(wrapper);

        assertThat(json).doesNotContain("meta");
        assertThat(json).contains("data");
    }

    @Test
    void error_jsonOmitsMeta() throws Exception {
        var wrapper = ApiResponseWrapper.error(
                "Not found", "NOT_FOUND", "PRODUCT_NOT_FOUND", Map.of("id", "123"));
        var json = mapper.writeValueAsString(wrapper);

        assertThat(json).doesNotContain("meta");
        assertThat(json).contains("\"domainCode\":\"PRODUCT_NOT_FOUND\"");
    }

    @Test
    void paginated_jsonContainsMeta() throws Exception {
        var wrapper = ApiResponseWrapper.paginated("data", 0, 10, 42L, 5);
        var json = mapper.writeValueAsString(wrapper);

        assertThat(json).contains("\"meta\"");
        assertThat(json).contains("\"page\":0");
        assertThat(json).contains("\"size\":10");
        assertThat(json).contains("\"totalElements\":42");
        assertThat(json).contains("\"totalPages\":5");
    }

    // ── v1s-15-9: paginated() input validation ─────────────────────────

    @Test
    void paginated_rejectsInvalidInputs() {
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", -1, 10, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", 0, 0, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", 0, 10, -1L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalElements");
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", 0, 10, 0L, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalPages");
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", 0, 10, 0L, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalElements is 0");
        assertThatThrownBy(() -> ApiResponseWrapper.paginated("d", 0, 10, 42L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalElements > 0");
    }

    @Test
    void paginated_acceptsSpringPageEdgeCases() {
        // Empty result: Spring reports page 0, totalPages 0.
        assertThat(ApiResponseWrapper.paginated("d", 0, 20, 0L, 0).meta()).isNotNull();
        // Last page of a non-empty result.
        assertThat(ApiResponseWrapper.paginated("d", 4, 10, 42L, 5).meta()).isNotNull();
        // Page past the end stays allowed: Spring returns it with empty content (GET ?page=99).
        assertThat(ApiResponseWrapper.paginated("d", 3, 20, 0L, 0).meta()).isNotNull();
        assertThat(ApiResponseWrapper.paginated("d", 99, 10, 42L, 5).meta()).isNotNull();
    }
}

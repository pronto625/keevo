package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}

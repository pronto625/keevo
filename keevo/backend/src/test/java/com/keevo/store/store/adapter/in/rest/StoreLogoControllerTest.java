package com.keevo.store.store.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoreLogoController — declared type (Story 19.12)")
class StoreLogoControllerTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/jpg", "image/pjpeg", "IMAGE/PNG",
            "image/jpeg; charset=binary", "application/octet-stream", ""})
    @DisplayName("accepted: png / jpeg variants / octet-stream, any case, parameters ignored")
    void accepted(String type) {
        assertThat(StoreLogoController.acceptedType(type)).isTrue();
        assertThat(StoreLogoController.acceptedType(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "text/plain", "image/webp", "not a type", "image/"})
    @DisplayName("refused: other types and unparsable values")
    void refused(String type) {
        assertThat(StoreLogoController.acceptedType(type)).isFalse();
    }
}

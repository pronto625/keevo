package com.keevo.shared.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD guard: ensures Story 2.4 required ErrorCode values exist.
 */
@DisplayName("ErrorCode completeness (Story 2.4)")
class ErrorCodeCompletenessStory24Test {

    @Test
    @DisplayName("CSV_PARSE_ERROR error code exists")
    void shouldContainCsvParseError() {
        Set<String> codes = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("CSV_PARSE_ERROR"),
                "ErrorCode.CSV_PARSE_ERROR must be defined for Story 2.4");
    }

    @Test
    @DisplayName("PRODUCT_NAME_ALREADY_EXISTS error code exists")
    void shouldContainProductNameAlreadyExists() {
        Set<String> codes = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("PRODUCT_NAME_ALREADY_EXISTS"),
                "ErrorCode.PRODUCT_NAME_ALREADY_EXISTS must be defined for Story 2.4");
    }
}

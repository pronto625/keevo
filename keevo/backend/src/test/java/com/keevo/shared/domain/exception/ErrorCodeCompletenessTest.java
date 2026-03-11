package com.keevo.shared.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD guard: ensures required ErrorCode values exist (Story 2.5).
 * Acts as a compile-time + runtime safety net — adding a new code
 * here forces implementation before this test can go GREEN.
 */
@DisplayName("ErrorCode completeness (Story 2.5)")
class ErrorCodeCompletenessTest {

    @Test
    @DisplayName("CLIENT_NOT_FOUND error code exists")
    void should_have_client_not_found() {
        Set<String> codes = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("CLIENT_NOT_FOUND"),
                "ErrorCode.CLIENT_NOT_FOUND must be defined for Story 2.5");
    }

    @Test
    @DisplayName("SUPPLIER_NOT_FOUND error code exists")
    void should_have_supplier_not_found() {
        Set<String> codes = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("SUPPLIER_NOT_FOUND"),
                "ErrorCode.SUPPLIER_NOT_FOUND must be defined for Story 2.5");
    }
}

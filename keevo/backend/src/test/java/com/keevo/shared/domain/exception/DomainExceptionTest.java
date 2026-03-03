package com.keevo.shared.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DomainExceptionTest — TDD RED test for ErrorCode-based constructors.
 *
 * <p>Written FIRST (Red): DomainException must accept {@link ErrorCode} enum
 * in addition to raw strings for type-safe domain error codes.
 */
@DisplayName("DomainException")
class DomainExceptionTest {

    @Test
    @DisplayName("should create exception with ErrorCode")
    void should_create_with_error_code() {
        DomainException ex = new DomainException(ErrorCode.INVALID_AMOUNT);
        assertThat(ex.getDomainCode()).isEqualTo("INVALID_AMOUNT");
        assertThat(ex.getMessage()).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    @DisplayName("should create exception with ErrorCode and message")
    void should_create_with_error_code_and_message() {
        DomainException ex = new DomainException(ErrorCode.INVALID_AMOUNT, "negative value");
        assertThat(ex.getDomainCode()).isEqualTo("INVALID_AMOUNT");
        assertThat(ex.getMessage()).contains("INVALID_AMOUNT");
        assertThat(ex.getMessage()).contains("negative value");
    }

    @Test
    @DisplayName("should create exception with ErrorCode and cause")
    void should_create_with_error_code_and_cause() {
        RuntimeException cause = new RuntimeException("root cause");
        DomainException ex = new DomainException(ErrorCode.INTERNAL_ERROR, cause);
        assertThat(ex.getDomainCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("should preserve backward compatibility with string domain code")
    void should_work_with_string_code() {
        DomainException ex = new DomainException("CUSTOM_CODE");
        assertThat(ex.getDomainCode()).isEqualTo("CUSTOM_CODE");
    }
}

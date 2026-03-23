package com.keevo.sync.sync.adapter.in.rest.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPushRequestDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validRequest_passesValidation() {
        var op = new SyncOperationDto("op-1", "CREATE_SALE", "entity-1",
                Map.of("key", "value"), Instant.now());
        var dto = new SyncPushRequestDto("device-1", List.of(op));

        var violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void missingDeviceId_failsValidation() {
        var op = new SyncOperationDto("op-1", "CREATE_SALE", "entity-1",
                Map.of("key", "value"), Instant.now());
        var dto = new SyncPushRequestDto(null, List.of(op));

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void nullOperationsList_failsValidation() {
        // With @NotNull: null operations must fail; empty list [] is now allowed (Story 5.4)
        var dto = new SyncPushRequestDto("device-1", null);

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void emptyOperationsList_passesValidation() {
        // Story 5.4: empty batch is a valid gate-check push — @NotNull allows []
        var dto = new SyncPushRequestDto("device-1", List.of());

        var violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void operationWithoutOperationType_failsValidation() {
        var op = new SyncOperationDto("op-1", null, "entity-1",
                Map.of(), Instant.now());
        var dto = new SyncPushRequestDto("device-1", List.of(op));

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void operationWithoutOperationId_failsValidation() {
        var op = new SyncOperationDto(null, "CREATE_SALE", "entity-1",
                Map.of(), Instant.now());
        var dto = new SyncPushRequestDto("device-1", List.of(op));

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }
}

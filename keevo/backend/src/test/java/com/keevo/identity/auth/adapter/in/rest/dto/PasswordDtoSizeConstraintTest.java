package com.keevo.identity.auth.adapter.in.rest.dto;

import com.keevo.identity.employee.adapter.in.rest.dto.SetPasswordRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.12 — every password field carries a 72-char cap (bcrypt limit), so an oversized
 * password is rejected by bean validation (422 VALIDATION_ERROR via @Valid) instead of reaching
 * BCrypt (IllegalArgumentException → 500).
 */
@DisplayName("Password DTO size caps (Story 12.12)")
class PasswordDtoSizeConstraintTest {

    private static final String OK = "Password12";
    private static final String MAX = "a1".repeat(36);        // 72 chars
    private static final String OVER = MAX + "x";             // 73 chars
    private static final String PHONE = "+237699000000";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static <T> Set<String> violatedFields(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("register: 72 chars accepted, 73 rejected")
    void registration() {
        assertThat(validator.validate(new RegistrationRequest(PHONE, MAX))).isEmpty();
        assertThat(violatedFields(validator.validate(new RegistrationRequest(PHONE, OVER))))
                .containsExactly("password");
    }

    @Test
    @DisplayName("login: 73 chars rejected")
    void login() {
        assertThat(validator.validate(new LoginRequest(PHONE, MAX))).isEmpty();
        assertThat(violatedFields(validator.validate(new LoginRequest(PHONE, OVER))))
                .containsExactly("password");
    }

    @Test
    @DisplayName("change-password: current and new capped at 72")
    void changePassword() {
        assertThat(validator.validate(new ChangePasswordRequestDto(MAX, MAX))).isEmpty();
        assertThat(violatedFields(validator.validate(new ChangePasswordRequestDto(OVER, OK))))
                .containsExactly("currentPassword");
        assertThat(violatedFields(validator.validate(new ChangePasswordRequestDto(OK, OVER))))
                .containsExactly("newPassword");
    }

    @Test
    @DisplayName("reset-password: new password capped at 72")
    void resetPassword() {
        assertThat(validator.validate(new ResetPasswordRequestDto(PHONE, "123456", MAX))).isEmpty();
        assertThat(violatedFields(validator.validate(new ResetPasswordRequestDto(PHONE, "123456", OVER))))
                .containsExactly("newPassword");
    }

    @Test
    @DisplayName("owner sets employee password: capped at 72")
    void setPassword() {
        assertThat(validator.validate(new SetPasswordRequestDto(MAX))).isEmpty();
        assertThat(violatedFields(validator.validate(new SetPasswordRequestDto(OVER))))
                .containsExactly("newPassword");
    }
}

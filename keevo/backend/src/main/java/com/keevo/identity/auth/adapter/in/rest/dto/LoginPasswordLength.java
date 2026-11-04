package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Story 19.4 — password length of {@link LoginRequest}, reported on the {@code password} field, by path: the phone
 * path keeps its 8–72 characters rule and message (V1); the username path accepts 1 to 200 characters (Bref members may
 * have 6-character passwords; the upper bound only refuses absurd bodies). Skipped when the request has neither or both
 * identifiers: the controller answers « Indiquez un numéro de téléphone ou un identifiant » then.
 */
@Documented
@Constraint(validatedBy = LoginPasswordLength.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginPasswordLength {

    String message() default "Le mot de passe doit contenir entre 8 et 72 caractères";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int PHONE_MIN = 8;
    int PHONE_MAX = 72;
    int USERNAME_MAX = 200;
    String USERNAME_MESSAGE = "Le mot de passe ne doit pas dépasser 200 caractères";

    /** Checks the password length of the path the request uses. */
    class Validator implements ConstraintValidator<LoginPasswordLength, LoginRequest> {

        @Override
        public boolean isValid(LoginRequest request, ConstraintValidatorContext context) {
            if (request == null || request.password() == null || request.hasPhoneNumber() == request.hasUsername()) {
                return true;
            }
            int length = request.password().length();
            String message;
            if (request.hasPhoneNumber()) {
                if (length >= PHONE_MIN && length <= PHONE_MAX) {
                    return true;
                }
                message = context.getDefaultConstraintMessageTemplate();
            } else {
                if (length <= USERNAME_MAX) {
                    return true;
                }
                message = USERNAME_MESSAGE;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode("password")
                    .addConstraintViolation();
            return false;
        }
    }
}

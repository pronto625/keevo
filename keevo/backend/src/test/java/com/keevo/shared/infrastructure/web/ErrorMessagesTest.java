package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 16.14 — one French catalogue for every error code, French validation errors, service reasons kept. */
@DisplayName("ErrorMessages — French catalogue (16.14)")
class ErrorMessagesTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("every ErrorCode constant has a French message")
    void everyErrorCodeHasAFrenchMessage() {
        List<String> missing = Arrays.stream(ErrorCode.values()).map(Enum::name)
                .filter(code -> ErrorMessages.of(code) == null || ErrorMessages.of(code).isBlank())
                .toList();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("ACCOUNT_SUSPENDED and MEMBERSHIP_NOT_FOUND have French messages")
    void previouslyMissingCodes() {
        assertThat(ErrorMessages.of("ACCOUNT_SUSPENDED")).isEqualTo("Compte suspendu : consultation seule");
        assertThat(ErrorMessages.of("MEMBERSHIP_NOT_FOUND")).isEqualTo("Vous n'avez pas accès à cette entreprise");
        ResponseEntity<ApiResponseWrapper<Void>> resp = handler.handleDomainException(
                new DomainException(ErrorCode.ACCOUNT_SUSPENDED, "Account deletion is not available while suspended"));
        assertThat(resp.getBody().error()).isEqualTo("Compte suspendu : consultation seule");
    }

    @Test
    @DisplayName("an opt-in French user message (userFacing) is the error; CORE_MODULE_REQUIRED names the module")
    void optInUserMessage_passedThrough() {
        ResponseEntity<ApiResponseWrapper<Void>> resp = handler.handleDomainException(DomainException.userFacing(
                ErrorCode.CORE_MODULE_REQUIRED, "Le module « Caisse » fait partie du socle et ne peut pas être désactivé"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().error())
                .isEqualTo("Le module « Caisse » fait partie du socle et ne peut pas être désactivé");

        ResponseEntity<ApiResponseWrapper<Void>> validation = handler.handleDomainException(DomainException.userFacing(
                ErrorCode.VALIDATION_ERROR, "Date de fin invalide", Map.of("expiresAt", "Date de fin invalide")));
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(validation.getBody().error()).isEqualTo("Date de fin invalide");
        assertThat(details(validation)).containsEntry("expiresAt", "Date de fin invalide");

        ResponseEntity<ApiResponseWrapper<Void>> notFound = handler.handleDomainException(
                DomainException.userFacing(ErrorCode.NOT_FOUND, "Paiement introuvable"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().error()).isEqualTo("Paiement introuvable");
    }

    @Test
    @DisplayName("a plain reason (English, technical, null) never reaches the user: catalogue text")
    void plainReason_catalogueText() {
        assertThat(handler.handleDomainException(new DomainException(ErrorCode.VALIDATION_ERROR,
                "Invalid tenant UUID: 3f2c")).getBody().error()).isEqualTo("Données invalides");
        assertThat(handler.handleDomainException(new DomainException(ErrorCode.NOT_FOUND,
                "Feedback not found: 3f2c")).getBody().error()).isEqualTo("Ressource introuvable");
        assertThat(handler.handleDomainException(new DomainException(ErrorCode.VALIDATION_ERROR, (String) null))
                .getBody().error()).isEqualTo("Données invalides");
        assertThat(handler.handleDomainException(new DomainException(ErrorCode.CORE_MODULE_REQUIRED,
                "Le module « Caisse » fait partie du socle")).getBody().error())
                .isEqualTo("Ce module fait partie du socle et ne peut pas être désactivé");
        assertThat(handler.handleDomainException(DomainException.userFacing(ErrorCode.VALIDATION_ERROR, null))
                .getBody().error()).isEqualTo("Données invalides");
    }

    @Test
    @DisplayName("PLAN_PROFILE_NOT_FOUND → 404 « Offre introuvable »")
    void planProfileNotFound() {
        ResponseEntity<ApiResponseWrapper<Void>> resp = handler.handleDomainException(
                new DomainException(ErrorCode.PLAN_PROFILE_NOT_FOUND, "Offre introuvable"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().error()).isEqualTo("Offre introuvable");
    }

    @Test
    @DisplayName("bean validation: error = the first field's message (sorted by field), details field → message")
    void beanValidation_firstFieldMessage() throws Exception {
        // reported in either order, the answer is the same: sorted by field name (password < phoneNumber)
        for (boolean phoneFirst : new boolean[] {true, false}) {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
            FieldError phone = new FieldError("request", "phoneNumber", "Numéro de téléphone invalide");
            FieldError password = new FieldError("request", "password", "Le mot de passe est obligatoire");
            result.addError(phoneFirst ? phone : password);
            result.addError(phoneFirst ? password : phone);
            assertThat(handler.handleValidation(new MethodArgumentNotValidException(param(), result)).getBody()
                    .error()).isEqualTo("Le mot de passe est obligatoire");
        }
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "phoneNumber", "Numéro de téléphone invalide"));
        result.addError(new FieldError("request", "password", "Le mot de passe est obligatoire"));
        ResponseEntity<ApiResponseWrapper<Void>> resp = handler.handleValidation(
                new MethodArgumentNotValidException(param(), result));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().domainCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(resp.getBody().error()).isEqualTo("Le mot de passe est obligatoire");
        assertThat(details(resp))
                .containsEntry("phoneNumber", "Numéro de téléphone invalide")
                .containsEntry("password", "Le mot de passe est obligatoire");
    }

    @Test
    @DisplayName("bean validation without a message: « Données invalides »; object error message used when no field")
    void beanValidation_fallbacks() throws Exception {
        BeanPropertyBindingResult empty = new BeanPropertyBindingResult(new Object(), "request");
        assertThat(handler.handleValidation(new MethodArgumentNotValidException(param(), empty)).getBody().error())
                .isEqualTo("Données invalides");

        BeanPropertyBindingResult global = new BeanPropertyBindingResult(new Object(), "request");
        global.addError(new ObjectError("request", "Le taux de TVA est requis"));
        assertThat(handler.handleValidation(new MethodArgumentNotValidException(param(), global)).getBody().error())
                .isEqualTo("Le taux de TVA est requis");
    }

    @SuppressWarnings("unused")
    private void target(@Valid Body body) {
    }

    private record Body(@NotBlank @Pattern(regexp = "x") String phoneNumber) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(ResponseEntity<ApiResponseWrapper<Void>> resp) {
        return (Map<String, Object>) resp.getBody().details();
    }

    private static MethodParameter param() throws NoSuchMethodException {
        return new MethodParameter(ErrorMessagesTest.class.getDeclaredMethod("target", Body.class), 0);
    }
}

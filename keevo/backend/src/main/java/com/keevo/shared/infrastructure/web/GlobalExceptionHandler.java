package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — Translates domain and validation exceptions to API responses.
 *
 * <p>Response format:
 * <pre>{@code
 * {
 *   "error": "Human readable message",
 *   "code": "HTTP_STATUS_NAME",
 *   "domainCode": "PRODUCT_NOT_FOUND",
 *   "details": { ... },
 *   "timestamp": "2026-03-02T00:00:00Z"
 * }
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * French user-facing error messages mapped by domain code.
     * These are shown in the {@code error} field of the API response.
     * NEVER expose technical details — all messages are human-readable French.
     */
    private static final Map<String, String> FR_MESSAGES = Map.ofEntries(
            Map.entry("USER_NOT_FOUND",           "Utilisateur introuvable"),
            Map.entry("USER_ALREADY_EXISTS",       "Ce numéro est déjà associé à un compte"),
            Map.entry("INVALID_CREDENTIALS",       "Téléphone ou mot de passe incorrect"),
            Map.entry("ACCOUNT_LOCKED",            "Compte temporairement bloqué, réessayez dans 15 minutes"),
            Map.entry("TOKEN_EXPIRED",             "Session expirée, veuillez vous reconnecter"),
            Map.entry("TOKEN_INVALID",             "Jeton d'authentification invalide"),
            Map.entry("UNAUTHORIZED",              "Accès non autorisé"),
            Map.entry("FORBIDDEN",                 "Vous n'avez pas les droits nécessaires"),
            Map.entry("TENANT_NOT_FOUND",          "Espace de travail introuvable"),
            Map.entry("PLAN_LIMIT_EXCEEDED",       "Limite de votre plan atteinte"),
            Map.entry("RATE_LIMIT_EXCEEDED",       "Trop de requêtes, veuillez patienter"),
            Map.entry("AUDIT_IMMUTABLE",           "Les entrées du journal d'audit ne peuvent pas être modifiées"),
            Map.entry("INSUFFICIENT_STOCK",        "Stock insuffisant pour cette opération"),
            Map.entry("STOCK_NOT_FOUND",           "Niveau de stock introuvable"),
            Map.entry("CLIENT_NOT_FOUND",          "Client introuvable"),
            Map.entry("SUPPLIER_NOT_FOUND",        "Fournisseur introuvable"),
            Map.entry("SUBSCRIPTION_NOT_FOUND",    "Aucun abonnement actif trouvé"),
            Map.entry("PRODUCT_NAME_ALREADY_EXISTS", "Un produit avec ce nom existe déjà dans votre catalogue"),
            Map.entry("WAREHOUSE_ALREADY_EXISTS",   "Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte."),
            Map.entry("STORE_NOT_FOUND",             "Boutique introuvable"),
            Map.entry("PRODUCT_NOT_FOUND",           "Produit introuvable"),
            Map.entry("STORE_NOT_ACTIVE",           "Cette boutique est désactivée"),
            Map.entry("SAME_SOURCE_DESTINATION",    "Source et destination doivent être différentes"),
            Map.entry("CSV_PARSE_ERROR",           "Erreur de lecture du fichier CSV"),
            Map.entry("EMPLOYEE_NOT_FOUND",        "Employé introuvable"),
            Map.entry("PHONE_ALREADY_REGISTERED",  "Ce numéro de téléphone est déjà utilisé par un autre compte"),
            Map.entry("CANNOT_CHANGE_OWN_ROLE",    "Vous ne pouvez pas modifier votre propre rôle"),
            Map.entry("CANNOT_DEMOTE_LAST_OWNER",  "Impossible de rétrograder le dernier propriétaire actif"),
            Map.entry("CANNOT_SET_OWN_PASSWORD",   "Vous ne pouvez pas définir votre propre mot de passe via cet endpoint"),
            Map.entry("PASSWORD_CHANGE_REQUIRED",  "Vous devez changer votre mot de passe avant de continuer"),
            Map.entry("STORE_REASSIGNED",          "Votre boutique a été modifiée, veuillez vous reconnecter"),
            Map.entry("ACCOUNT_INACTIVE",          "Ce compte a été désactivé"),
            Map.entry("SESSION_REVOKED",           "Votre session a été révoquée, veuillez vous reconnecter"),
            Map.entry("SALE_NOT_FOUND",            "Vente introuvable"),
            Map.entry("SALE_ALREADY_EXISTS",       "Cette vente a déjà été enregistrée"),
            Map.entry("VALIDATION_FAILED",         "Données invalides"),
            Map.entry("VALIDATION_ERROR",          "Données invalides"),
            Map.entry("DISCOUNT_EXCEEDS_SUBTOTAL","La réduction dépasse le sous-total"),
            Map.entry("SALE_NOT_PENDING",          "Cette vente n'est pas en attente de validation"),
            Map.entry("JUSTIFICATION_REQUIRED",    "Une justification est requise"),
            Map.entry("JUSTIFICATION_TOO_SHORT",   "La justification doit contenir au moins 10 caractères"),
            Map.entry("SALE_ALREADY_CANCELLED",    "Cette vente a déjà été annulée"),
            Map.entry("SALE_NOT_COMPLETED",        "Seule une vente complétée peut être corrigée"),
            Map.entry("INTERNAL_ERROR",            "Une erreur inattendue s'est produite"),
            Map.entry("DAY_ALREADY_CLOSED",        "La journée a déjà été clôturée pour cette boutique"),
            Map.entry("MISSING_PARAMETER",         "Paramètre requis manquant"),
            Map.entry("INVALID_OR_EXPIRED_CODE", "Code invalide ou expiré"),
            Map.entry("CODE_LOCKED",             "Trop de tentatives, demandez un nouveau code"),
            Map.entry("SYNC_REQUIRED",              "Synchronisation requise — données trop anciennes"),
            Map.entry("DEVICE_ID_MISMATCH",       "Ce device n'est pas associé à votre compte"),
            Map.entry("INVENTORY_SESSION_ALREADY_ACTIVE", "Un inventaire est déjà en cours pour cette boutique"),
            Map.entry("INVENTORY_SESSION_NOT_FOUND",     "Session d'inventaire introuvable"),
            Map.entry("INVENTORY_SESSION_NOT_IN_PROGRESS", "Cette session d'inventaire n'est pas en cours"),
            Map.entry("INVENTORY_STORE_NOT_FOUND",       "Boutique cible introuvable"),
            Map.entry("INVENTORY_INVALID_CATEGORIES",    "Une ou plusieurs catégories sont invalides"),
            Map.entry("WHATSAPP_DELIVERY_FAILED",         "Envoi WhatsApp échoué. Vérifiez votre connexion et réessayez."),
            Map.entry("OPTIMISTIC_LOCK",              "Cette donnée a été modifiée par une autre opération, veuillez réessayer")
    );

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleDomainException(DomainException ex) {
        HttpStatus status = domainCodeToHttpStatus(ex.getDomainCode());
        String userMessage = FR_MESSAGES.getOrDefault(ex.getDomainCode(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(ApiResponseWrapper.error(
                        userMessage,
                        status.name(),
                        ex.getDomainCode(),
                        ex.getDetails()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponseWrapper.error(
                        "Validation failed",
                        HttpStatus.UNPROCESSABLE_ENTITY.name(),
                        "VALIDATION_ERROR",
                        fieldErrors
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.error(
                        "Paramètre invalide : " + ex.getName(),
                        HttpStatus.BAD_REQUEST.name(),
                        "VALIDATION_ERROR",
                        null
                ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.error(
                        "Paramètre requis manquant : " + ex.getParameterName(),
                        HttpStatus.BAD_REQUEST.name(),
                        "MISSING_PARAMETER",
                        null
                ));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleAccessDenied(AuthorizationDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponseWrapper.error(
                        FR_MESSAGES.getOrDefault("FORBIDDEN", "Vous n'avez pas les droits nécessaires"),
                        HttpStatus.FORBIDDEN.name(),
                        "FORBIDDEN",
                        null
                ));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleOptimisticLock(
            OptimisticLockingFailureException ex) {
        log.warn("OPTIMISTIC_LOCK: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponseWrapper.error(
                        FR_MESSAGES.getOrDefault("OPTIMISTIC_LOCK", "Conflit de version"),
                        HttpStatus.CONFLICT.name(),
                        "OPTIMISTIC_LOCK",
                        null
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception — {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        String userMessage = FR_MESSAGES.getOrDefault("INTERNAL_ERROR", "Une erreur inattendue s'est produite");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.error(
                        userMessage,
                        HttpStatus.INTERNAL_SERVER_ERROR.name(),
                        "INTERNAL_ERROR",
                        null
                ));
    }

    private HttpStatus domainCodeToHttpStatus(String domainCode) {
        if (domainCode == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        return switch (domainCode) {
            case "USER_NOT_FOUND", "TENANT_NOT_FOUND",
                 "PRODUCT_NOT_FOUND", "CATEGORY_NOT_FOUND",
                 "CLIENT_NOT_FOUND", "SUPPLIER_NOT_FOUND",
                 "STOCK_NOT_FOUND", "STORE_NOT_FOUND",
                 "EMPLOYEE_NOT_FOUND",
                 "MEMBERSHIP_NOT_FOUND",
                 "SUBSCRIPTION_NOT_FOUND",
                 "SALE_NOT_FOUND",
                 "INVENTORY_SESSION_NOT_FOUND",
                 "INVENTORY_STORE_NOT_FOUND",
                 "REPORT_NOT_FOUND",
                 "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "UNAUTHORIZED", "TOKEN_EXPIRED",
                 "TOKEN_INVALID",
                 "INVALID_CREDENTIALS", "ACCOUNT_LOCKED",
                 "REFRESH_TOKEN_INVALID",
                 "ACCOUNT_INACTIVE",
                 "STORE_REASSIGNED",
                 "SESSION_REVOKED" -> HttpStatus.UNAUTHORIZED;
            case "EMAIL_ALREADY_EXISTS", "USER_ALREADY_EXISTS",
                 "TENANT_ALREADY_EXISTS",
                 "MEMBERSHIP_ALREADY_EXISTS",
                 "PRODUCT_NAME_ALREADY_EXISTS",
                 "WAREHOUSE_ALREADY_EXISTS",
                 "SALE_ALREADY_EXISTS",
                 "DAY_ALREADY_CLOSED",
                 "INVENTORY_SESSION_ALREADY_ACTIVE",
                 "INVENTORY_SESSION_NOT_IN_PROGRESS",
                 "REPORT_ALREADY_SENT",
                 "SALE_ALREADY_CANCELLED",
                 "OPTIMISTIC_LOCK",
                 "PHONE_ALREADY_REGISTERED" -> HttpStatus.CONFLICT;  // Story 2.4 / 3.1 / 4.1 / 4.4 / 6.1 / 7.2 / v1s-13-1 / v1s-13-5 / v1s-14-11
            case "VALIDATION_ERROR", "INVALID_AMOUNT",
                 "INVALID_PHONE_NUMBER", "INVALID_PASSWORD",
                 "INSUFFICIENT_STOCK",
                 "STORE_NOT_ACTIVE",
                 "VALIDATION_FAILED",
                 "SAME_SOURCE_DESTINATION",
                 "DISCOUNT_EXCEEDS_SUBTOTAL",
                 "SALE_NOT_PENDING",
                 "SALE_NOT_COMPLETED",
                 "INVALID_OR_EXPIRED_CODE",
                 "CODE_LOCKED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "JUSTIFICATION_REQUIRED",
                 "JUSTIFICATION_TOO_SHORT" -> HttpStatus.BAD_REQUEST;
            case "SECTOR_TEMPLATE_NOT_FOUND",
                 "ONBOARDING_ALREADY_COMPLETED",
                 "INVENTORY_INVALID_CATEGORIES",
                 "INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE" -> HttpStatus.BAD_REQUEST;
            case "TENANT_PROVISION_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "CSV_PARSE_ERROR" -> HttpStatus.UNPROCESSABLE_ENTITY;  // Story 2.4 malformed CSV
            case "PLAN_LIMIT_EXCEEDED",
                 "ACCOUNT_SUSPENDED",
                 "AUDIT_IMMUTABLE",
                 "PASSWORD_CHANGE_REQUIRED",
                 "FORBIDDEN",
                 "DEVICE_ID_MISMATCH",
                 "CANNOT_CHANGE_OWN_ROLE",
                 "CANNOT_DEMOTE_LAST_OWNER",
                 "CANNOT_SET_OWN_PASSWORD" -> HttpStatus.FORBIDDEN;   // H2 fix: role mismatch is 403, not 401; AUDIT_IMMUTABLE = immutable log; DEVICE_ID_MISMATCH = deviceId belongs to another user (v1s-13-2); CANNOT_* = role anti-lockout (v1s-14-11)
            case "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "WHATSAPP_DELIVERY_FAILED" -> HttpStatus.BAD_GATEWAY;  // upstream WhatsApp failure
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

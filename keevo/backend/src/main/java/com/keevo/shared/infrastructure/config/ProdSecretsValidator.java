package com.keevo.shared.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ProdSecretsValidator — Fail-fast guard for required production secrets.
 *
 * <p>Active only in {@code prod} and {@code staging} profiles.
 * Prevents boot if mandatory secrets are absent, empty, or insecure.
 *
 * <p>Checks performed:
 * <ul>
 *   <li>{@code ADMIN_PASSWORD} must not be blank, null, or the known default {@code Admin@1234!}</li>
 *   <li>{@code KEEVO_JWT_PRIVATE_KEY_PATH} must be an absolute file path (not {@code classpath:})</li>
 *   <li>{@code KEEVO_JWT_PUBLIC_KEY_PATH} must be an absolute file path (not {@code classpath:})</li>
 * </ul>
 *
 * <p>Story 10.3 — AC1, AC2, AC3.
 *
 * <p>Architecture: shared infrastructure config — no business logic.
 * Strategy pattern: this bean is the "strict" strategy for prod/staging;
 * dev/test profiles have no validator (permissive by absence).
 */
@Component
@Profile({"prod", "staging"})
public class ProdSecretsValidator {

    private static final Logger log = LoggerFactory.getLogger(ProdSecretsValidator.class);
    private static final String KNOWN_DEFAULT_PASSWORD = "Admin@1234!";

    private final AdminProperties adminProperties;
    private final String jwtPrivateKeyPath;
    private final String jwtPublicKeyPath;

    public ProdSecretsValidator(
            AdminProperties adminProperties,
            @Value("${keevo.jwt.private-key-path}") String jwtPrivateKeyPath,
            @Value("${keevo.jwt.public-key-path}") String jwtPublicKeyPath) {
        this.adminProperties = adminProperties;
        this.jwtPrivateKeyPath = jwtPrivateKeyPath;
        this.jwtPublicKeyPath = jwtPublicKeyPath;
    }

    @PostConstruct
    public void validate() {
        log.info("[FailFast] Validating production secrets...");
        validateAdminPassword();
        validateJwtKeys();
        log.info("[FailFast] Production secrets validation passed.");
    }

    private void validateAdminPassword() {
        String pwd = adminProperties.password();
        if (isInsecurePassword(pwd)) {
            throw new IllegalStateException(
                    "[FailFast] ADMIN_PASSWORD is required in production — do not use the default value. "
                            + "Set the ADMIN_PASSWORD environment variable to a strong, unique password.");
        }
        log.info("[FailFast] ADMIN_PASSWORD ✓");
    }

    private void validateJwtKeys() {
        validateJwtKey("KEEVO_JWT_PRIVATE_KEY_PATH", jwtPrivateKeyPath);
        validateJwtKey("KEEVO_JWT_PUBLIC_KEY_PATH", jwtPublicKeyPath);
        log.info("[FailFast] JWT key paths ✓");
    }

    private static void validateJwtKey(String envVar, String keyPath) {
        if (isInvalidKeyPath(keyPath)) {
            throw new IllegalStateException(
                    "[FailFast] JWT key must be a file path in production, not classpath:. "
                            + "Set " + envVar + " to the absolute path of the PEM file mounted in the container.");
        }
        // AC3: the path must point to a readable, non-empty file — fail fast at boot,
        // not later when the first token is signed/verified.
        if (isMissingOrEmptyFile(keyPath)) {
            throw new IllegalStateException(
                    "[FailFast] JWT key not found or empty — " + envVar + " points to a missing/unreadable/empty file.");
        }
    }

    private static boolean isInsecurePassword(String password) {
        return password == null || password.isBlank() || KNOWN_DEFAULT_PASSWORD.equals(password);
    }

    private static boolean isInvalidKeyPath(String keyPath) {
        return keyPath == null || keyPath.isBlank() || keyPath.startsWith("classpath:");
    }

    private static boolean isMissingOrEmptyFile(String keyPath) {
        Path path = Path.of(keyPath.startsWith("file:") ? keyPath.substring("file:".length()) : keyPath);
        try {
            return !Files.isReadable(path) || Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }
}

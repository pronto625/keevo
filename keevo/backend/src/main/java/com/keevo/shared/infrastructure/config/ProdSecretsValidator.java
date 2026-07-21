package com.keevo.shared.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

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
        validatePrivateKeyNotWorldReadable("KEEVO_JWT_PRIVATE_KEY_PATH", jwtPrivateKeyPath);
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

    /**
     * Defense-in-depth: reject a private key file that is world-readable (OTHERS_READ).
     *
     * <p>Group-read ({@code GROUP_READ}) is <em>permitted</em>: the non-root {@code keevo}
     * container (UID/GID 1001) reads the key via group membership — the deploy script sets
     * {@code chgrp 1001} + {@code chmod 640} so the container (group 1001) can read while
     * the file is not world-readable. Only OTHERS_READ (true world-readability) is rejected.
     *
     * <p>This runs as the container user, which is the file's group owner in the happy path,
     * so it mainly catches a <em>manual</em> regression — someone re-permissioning the host
     * file to 644/646 outside the deploy pipeline and rebooting. The CI pre-flight assertion
     * ({@code stat -c %a} + container-read check in {@code deploy-backend.yml}) is the primary
     * enforcement at deploy time; this method is the boot-time backstop.
     *
     * <p>Only applies to {@code file:} paths ({@code classpath:} is rejected upstream). On
     * non-POSIX filesystems the check is skipped (graceful degradation).
     *
     * <p>Story 12.1 Task 4 — NFR11 / ARCH16-17 enforcement.
     */
    private static void validatePrivateKeyNotWorldReadable(String envVar, String keyPath) {
        // Only check file: paths — classpath: is already rejected upstream.
        if (keyPath == null || keyPath.isBlank() || keyPath.startsWith("classpath:")) {
            return;
        }
        Path path = Path.of(keyPath.startsWith("file:") ? keyPath.substring("file:".length()) : keyPath);
        try {
            // NOFOLLOW_LINKS: inspect the link/file itself, not a symlink target (symlink swap).
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                throw new IllegalStateException(
                        "[FailFast] " + envVar + " private key must not be world-readable "
                                + "(others-read detected; mode should be 640, group=keevo-jwt). "
                                + "Ensure chmod 640 and chgrp 1001 (keevo-jwt) on the host.");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[FailFast] Cannot read permissions of " + envVar + " private key: " + e.getMessage());
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — skip the check gracefully.
            log.warn("[FailFast] POSIX file permissions not supported on this filesystem — "
                    + "skipping world-readability check for private key. "
                    + "CI pre-flight assertion is the primary enforcement.");
        }
    }
}

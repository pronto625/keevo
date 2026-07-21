package com.keevo.shared.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProdSecretsValidatorTest — Unit tests for the production secrets fail-fast guard.
 *
 * <p>Tests the validator directly (no Spring context) with various combinations
 * of admin password and JWT key paths. JWT "valid" cases use real temp files
 * because the validator (AC3) checks file readability and non-emptiness.
 *
 * <p>Story 10.3 — AC1, AC2, AC3, AC7, AC8.
 */
@DisplayName("ProdSecretsValidator — fail-fast guard")
class ProdSecretsValidatorTest {

    @TempDir
    Path keyDir;

    private String privateKey;
    private String publicKey;

    @BeforeEach
    void createRealKeyFiles() throws Exception {
        Path priv = Files.writeString(keyDir.resolve("private.pem"), "-----BEGIN PRIVATE KEY-----\n");
        Path pub = Files.writeString(keyDir.resolve("public.pem"), "-----BEGIN PUBLIC KEY-----\n");
        // Private key must be mode 600 (owner-only) for the world-readability check (story 12.1).
        Files.setPosixFilePermissions(priv, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        privateKey = priv.toAbsolutePath().toString();
        publicKey = pub.toAbsolutePath().toString();
    }

    // ── ADMIN_PASSWORD validation ───────────────────────────────────────────

    @Nested
    @DisplayName("ADMIN_PASSWORD validation")
    class AdminPasswordValidation {

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is blank (empty string)")
        void throwsWhenPasswordBlank() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", ""), privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is null")
        void throwsWhenPasswordNull() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", null), privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is the known insecure default")
        void throwsWhenPasswordIsKnownDefault() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Admin@1234!"), privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC8: passes when ADMIN_PASSWORD is a strong non-default value")
        void passesWhenPasswordIsStrong() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"), privateKey, publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }

    // ── JWT key paths validation ───────────────────────────────────────────

    @Nested
    @DisplayName("JWT key paths validation")
    class JwtKeyPathsValidation {

        @Test
        @DisplayName("AC3: throws when JWT private key path is classpath: (dev path used in prod)")
        void throwsWhenJwtPrivateKeyIsClasspath() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    "classpath:keys/private_key.pem", publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PRIVATE_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT public key path is classpath: (dev path used in prod)")
        void throwsWhenJwtPublicKeyIsClasspath() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    privateKey, "classpath:keys/public_key.pem");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PUBLIC_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT private key path is blank")
        void throwsWhenJwtPrivateKeyIsBlank() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"), "", publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PRIVATE_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT public key path is null")
        void throwsWhenJwtPublicKeyIsNull() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"), privateKey, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PUBLIC_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT key path points to a missing file")
        void throwsWhenJwtKeyFileMissing() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    keyDir.resolve("does-not-exist.pem").toAbsolutePath().toString(), publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found or empty");
        }

        @Test
        @DisplayName("AC3: throws when JWT key file is empty")
        void throwsWhenJwtKeyFileEmpty() throws Exception {
            Path empty = Files.createFile(keyDir.resolve("empty.pem"));
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    privateKey, empty.toAbsolutePath().toString());

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found or empty");
        }
    }

    // ── JWT key permissions validation (story 12.1 Task 4) ──────────────────

    @Nested
    @DisplayName("JWT private key world-readability check")
    class JwtKeyPermissionsValidation {

        @Test
        @DisplayName("story 12.1: rejects private key that is world-readable (OTHERS_READ)")
        void shouldRejectWorldReadablePrivateKey() throws Exception {
            // Create a private key with 644 (world-readable) — this must be rejected.
            Path worldReadableKey = Files.writeString(
                    keyDir.resolve("world-readable.pem"), "-----BEGIN PRIVATE KEY-----\n");
            Files.setPosixFilePermissions(worldReadableKey, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OTHERS_READ));  // 604 — world-readable

            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    worldReadableKey.toAbsolutePath().toString(), publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("world-readable");
        }

        @Test
        @DisplayName("story 12.1: accepts private key that is group-readable (640, GROUP_READ) — prod mode")
        void shouldAcceptGroupReadablePrivateKey() throws Exception {
            // The prod strategy is chgrp 1001 + chmod 640: the container (group 1001) reads
            // via GROUP_READ. Only OTHERS_READ (true world-readability) is rejected, so 640
            // must be accepted (review fix — the original check wrongly rejected GROUP_READ).
            Path groupReadableKey = Files.writeString(
                    keyDir.resolve("group-readable.pem"), "-----BEGIN PRIVATE KEY-----\n");
            Files.setPosixFilePermissions(groupReadableKey, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));  // 640 — group-readable (prod mode)

            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    groupReadableKey.toAbsolutePath().toString(), publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("story 12.1: accepts private key with mode 600 (owner-only)")
        void shouldAcceptMode600PrivateKey() throws Exception {
            // The default setup already creates a 600 private key — re-verify explicitly.
            Path safeKey = Files.writeString(
                    keyDir.resolve("safe.pem"), "-----BEGIN PRIVATE KEY-----\n");
            Files.setPosixFilePermissions(safeKey, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));  // 600

            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"),
                    safeKey.toAbsolutePath().toString(), publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path — all secrets properly provided")
    class HappyPath {

        @Test
        @DisplayName("passes when all production secrets are valid")
        void passesWhenAllSecretsAreValid() {
            var validator = new ProdSecretsValidator(
                    new AdminProperties("+237600000000", "Str0ngPr0dP@ss!"), privateKey, publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }
}

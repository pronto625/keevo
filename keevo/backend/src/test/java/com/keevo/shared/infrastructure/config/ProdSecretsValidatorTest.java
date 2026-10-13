package com.keevo.shared.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ProdSecretsValidatorTest — Unit tests for the production secrets fail-fast guard.
 *
 * <p>Tests the validator directly (no Spring context) with various combinations
 * of admin password and JWT key paths. JWT "valid" cases use real temp files
 * because the validator (AC3) checks file readability and non-emptiness.
 *
 * <p>Story 10.3 — AC1, AC2, AC3, AC7, AC8. Story v1s-12-13 — DB password, admin policy,
 * WhatsApp provider tokens (I/O matrix of the spec).
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
        // v1s-15-7: on non-POSIX runners the validator skips that check, so the fixture only
        // sets the mode where it is supported — the non-permission tests still run everywhere.
        if (posixSupported()) {
            Files.setPosixFilePermissions(priv, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        privateKey = priv.toAbsolutePath().toString();
        publicKey = pub.toAbsolutePath().toString();
    }

    private static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    // ── Builder helper (story v1s-12-13: keeps the 9-arg ctor out of every test) ──

    private static final String VALID_ADMIN_PASSWORD = "Str0ngPr0dP@ss!";
    private static final String VALID_DB_PASSWORD = "9f2c4e7a1b3d5f60a8c2e4f6";

    private Builder builder() {
        return new Builder();
    }

    /** Validator with the given admin password / key paths and every other secret valid. */
    private ProdSecretsValidator validator(String adminPassword, String privKey, String pubKey) {
        return builder().adminPassword(adminPassword).privateKey(privKey).publicKey(pubKey).build();
    }

    private final class Builder {
        private String adminPassword = VALID_ADMIN_PASSWORD;
        private String privKey = privateKey;
        private String pubKey = publicKey;
        private String dbPassword = VALID_DB_PASSWORD;
        private String provider = "noop";
        private String wassenderUrl = "";
        private String wassenderToken = "";
        private String twilioSid = "";
        private String twilioToken = "";
        private String twilioFrom = "";
        private String dashboardOrigin = "https://admin.keevo.cm";

        Builder adminPassword(String v) { adminPassword = v; return this; }
        Builder privateKey(String v) { privKey = v; return this; }
        Builder publicKey(String v) { pubKey = v; return this; }
        Builder dbPassword(String v) { dbPassword = v; return this; }
        Builder provider(String v) { provider = v; return this; }
        Builder wassender(String url, String token) { wassenderUrl = url; wassenderToken = token; return this; }
        Builder twilio(String sid, String token) {
            twilioSid = sid; twilioToken = token; twilioFrom = "+14155238886"; return this;
        }
        Builder twilioFrom(String v) { twilioFrom = v; return this; }
        Builder dashboardOrigin(String v) { dashboardOrigin = v; return this; }

        ProdSecretsValidator build() {
            return new ProdSecretsValidator(
                    new AdminProperties("+237600000000", adminPassword), privKey, pubKey,
                    dbPassword, provider, wassenderUrl, wassenderToken, twilioSid, twilioToken, twilioFrom,
                    dashboardOrigin);
        }
    }

    private static void assertRejects(ProdSecretsValidator validator, String envVar, String secret) {
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[FailFast]")
                .hasMessageContaining(envVar)
                .satisfies(e -> {
                    // Skip when the secret is a substring of the env var name itself (e.g. "PASSWORD").
                    if (secret != null && !secret.isBlank() && !envVar.contains(secret)) {
                        assertThat(e.getMessage()).doesNotContain(secret);
                    }
                });
    }

    // ── ADMIN_PASSWORD validation ───────────────────────────────────────────

    @Nested
    @DisplayName("ADMIN_PASSWORD validation")
    class AdminPasswordValidation {

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is blank (empty string)")
        void throwsWhenPasswordBlank() {
            var validator = validator("", privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is null")
        void throwsWhenPasswordNull() {
            var validator = validator(null, privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC7: throws when ADMIN_PASSWORD is the known insecure default")
        void throwsWhenPasswordIsKnownDefault() {
            var validator = validator("Admin@1234!", privateKey, publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("AC8: passes when ADMIN_PASSWORD is a strong non-default value")
        void passesWhenPasswordIsStrong() {
            var validator = validator("Str0ngPr0dP@ss!", privateKey, publicKey);

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
            var validator = validator("Str0ngPr0dP@ss!", "classpath:keys/private_key.pem", publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PRIVATE_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT public key path is classpath: (dev path used in prod)")
        void throwsWhenJwtPublicKeyIsClasspath() {
            var validator = validator("Str0ngPr0dP@ss!", privateKey, "classpath:keys/public_key.pem");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PUBLIC_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT private key path is blank")
        void throwsWhenJwtPrivateKeyIsBlank() {
            var validator = validator("Str0ngPr0dP@ss!", "", publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PRIVATE_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT public key path is null")
        void throwsWhenJwtPublicKeyIsNull() {
            var validator = validator("Str0ngPr0dP@ss!", privateKey, null);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEEVO_JWT_PUBLIC_KEY_PATH");
        }

        @Test
        @DisplayName("AC3: throws when JWT key path points to a missing file")
        void throwsWhenJwtKeyFileMissing() {
            var validator = validator("Str0ngPr0dP@ss!", keyDir.resolve("does-not-exist.pem").toAbsolutePath().toString(), publicKey);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found or empty");
        }

        @Test
        @DisplayName("AC3: throws when JWT key file is empty")
        void throwsWhenJwtKeyFileEmpty() throws Exception {
            Path empty = Files.createFile(keyDir.resolve("empty.pem"));
            var validator = validator("Str0ngPr0dP@ss!", privateKey, empty.toAbsolutePath().toString());

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found or empty");
        }
    }

    // ── JWT key permissions validation (story 12.1 Task 4) ──────────────────

    @Nested
    @DisplayName("JWT private key world-readability check")
    class JwtKeyPermissionsValidation {

        /** v1s-15-7: these tests set POSIX modes — skip (not fail) on non-POSIX runners. */
        @BeforeEach
        void requirePosix() {
            assumeTrue(posixSupported(), "POSIX file permissions not supported on this filesystem");
        }

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

            var validator = validator("Str0ngPr0dP@ss!", worldReadableKey.toAbsolutePath().toString(), publicKey);

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

            var validator = validator("Str0ngPr0dP@ss!", groupReadableKey.toAbsolutePath().toString(), publicKey);

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

            var validator = validator("Str0ngPr0dP@ss!", safeKey.toAbsolutePath().toString(), publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }

    // ── POSTGRES_PASSWORD validation (story v1s-12-13) ─────────────────────

    @Nested
    @DisplayName("POSTGRES_PASSWORD validation")
    class DatabasePasswordValidation {

        @ParameterizedTest(name = "rejects blank/null DB password [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void rejectsMissing(String pwd) {
            assertRejects(builder().dbPassword(pwd).build(), "POSTGRES_PASSWORD", pwd);
        }

        @ParameterizedTest(name = "rejects dev default [{0}]")
        @ValueSource(strings = {"keevo_local_pwd", "change_me_in_dot_env", "ci_password", "postgres",
                "keevo", "password", "KEEVO_LOCAL_PWD", "Postgres", "PASSWORD"})
        void rejectsDevDefaults(String pwd) {
            assertRejects(builder().dbPassword(pwd).build(), "POSTGRES_PASSWORD", pwd);
        }

        @Test
        @DisplayName("v1s-12-13 follow-up: default padded with Unicode spaces is still denylisted (strip, not trim)")
        void rejectsDefaultPaddedWithUnicodeSpaces() {
            String padded = "\u3000keevo_local_pwd\u2003";
            assertRejects(builder().dbPassword(padded).build(), "POSTGRES_PASSWORD", padded);
        }

        @ParameterizedTest(name = "rejects placeholder [{0}]")
        @ValueSource(strings = {"my_change_me_password", "CHANGEME_please_123", "your_db_password_here",
                "replace_with_secret_value", "db-placeholder-value", "xxxxxxxxxxxxxxxx"})
        void rejectsPlaceholders(String pwd) {
            assertRejects(builder().dbPassword(pwd).build(), "POSTGRES_PASSWORD", pwd);
        }

        @Test
        @DisplayName("rejects a DB password shorter than 12 characters")
        void rejectsShort() {
            assertRejects(builder().dbPassword("a1b2c3d4e5f").build(), "POSTGRES_PASSWORD", "a1b2c3d4e5f");
        }

        @Test
        @DisplayName("accepts a 12-char generated DB password (no class policy on DB)")
        void acceptsTwelveChars() {
            assertThatCode(builder().dbPassword("a1b2c3d4e5f6").build()::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts an openssl rand -hex 16 style DB password")
        void acceptsGenerated() {
            assertThatCode(builder().dbPassword("3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e").build()::validate)
                    .doesNotThrowAnyException();
        }
    }

    // ── ADMIN_PASSWORD strength policy (story v1s-12-13) ───────────────────

    @Nested
    @DisplayName("ADMIN_PASSWORD strength policy")
    class AdminPasswordPolicy {

        @Test
        @DisplayName("rejects the .env.example placeholder change_me_strong_password")
        void rejectsEnvExamplePlaceholder() {
            assertRejects(builder().adminPassword("change_me_strong_password").build(),
                    "ADMIN_PASSWORD", "change_me_strong_password");
        }

        @ParameterizedTest(name = "rejects placeholder [{0}]")
        @ValueSource(strings = {"Your_Admin_Pass1!", "Replace_With_Strong1!", "PlaceHolder-Pass1!"})
        void rejectsPlaceholders(String pwd) {
            assertRejects(builder().adminPassword(pwd).build(), "ADMIN_PASSWORD", pwd);
        }

        @Test
        @DisplayName("rejects the known default case-insensitively")
        void rejectsKnownDefaultCaseInsensitive() {
            assertRejects(builder().adminPassword("ADMIN@1234!").build(), "ADMIN_PASSWORD", "ADMIN@1234!");
        }

        @Test
        @DisplayName("leading/trailing whitespace does not count toward the 12-char minimum (admin + DB)")
        void whitespacePaddingDoesNotCount() {
            String paddedAdmin = "Ab1" + " ".repeat(9);
            assertRejects(builder().adminPassword(paddedAdmin).build(), "ADMIN_PASSWORD", null);
            String paddedDb = " ".repeat(9) + "a1b";
            assertRejects(builder().dbPassword(paddedDb).build(), "POSTGRES_PASSWORD", null);
        }

        @Test
        @DisplayName("rejects fewer than 12 characters even with 4 classes")
        void rejectsShort() {
            assertRejects(builder().adminPassword("Sh0rt@Pwd1!").build(), "ADMIN_PASSWORD", "Sh0rt@Pwd1!");
        }

        @ParameterizedTest(name = "rejects fewer than 3 character classes [{0}]")
        @ValueSource(strings = {"alllowercaseletters", "lowercaseand12345", "ALLUPPER@#$%^&*", "123456789012!"})
        void rejectsFewClasses(String pwd) {
            assertRejects(builder().adminPassword(pwd).build(), "ADMIN_PASSWORD", pwd);
        }

        @ParameterizedTest(name = "accepts 3-of-4 classes [{0}]")
        @ValueSource(strings = {"lowerUPPER1234", "lower1234!@#$", "UPPER1234!@#$", "lowerUPPER!@#$"})
        void acceptsThreeClasses(String pwd) {
            assertThatCode(builder().adminPassword(pwd).build()::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a password over 72 UTF-8 bytes (BCrypt limit)")
        void rejectsOverBcryptLimit() {
            String pwd = "Aa1!" + "é".repeat(35); // 4 + 70 bytes = 74 bytes, 39 chars
            assertRejects(builder().adminPassword(pwd).build(), "ADMIN_PASSWORD", pwd);
        }

        @Test
        @DisplayName("accepts a password of exactly 72 bytes")
        void acceptsExactlyBcryptLimit() {
            String pwd = "Aa1!" + "b".repeat(68);
            assertThatCode(builder().adminPassword(pwd).build()::validate).doesNotThrowAnyException();
        }
    }

    // ── WhatsApp provider tokens (story v1s-12-13) ─────────────────────────

    @Nested
    @DisplayName("WhatsApp provider tokens")
    class WhatsAppProviderValidation {

        private static final String WASSENDER_URL = "https://www.wasenderapi.com";
        private static final String WASSENDER_TOKEN = "wsd_9a8b7c6d5e4f3a2b1c";
        private static final String TWILIO_SID = "AC0123456789abcdef0123456789abcdef";
        private static final String TWILIO_TOKEN = "0123456789abcdef0123456789abcdef";

        @ParameterizedTest(name = "noop/unset provider passes with blank tokens [{0}]")
        @NullSource
        @ValueSource(strings = {"noop", "NOOP"})
        void noopPasses(String provider) {
            assertThatCode(builder().provider(provider).build()::validate).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "empty/padded provider is rejected (no adapter would match) [{0}]")
        @ValueSource(strings = {"", " twilio "})
        void emptyOrPaddedProviderRejected(String provider) {
            assertRejects(builder().provider(provider).twilio(TWILIO_SID, TWILIO_TOKEN).build(),
                    "KEEVO_WHATSAPP_PROVIDER", null);
        }

        @ParameterizedTest(name = "twilio from-number blank/placeholder [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"your_twilio_number"})
        void twilioFromNumberInvalid(String from) {
            assertRejects(builder().provider("twilio").twilio(TWILIO_SID, TWILIO_TOKEN).twilioFrom(from).build(),
                    "TWILIO_FROM_NUMBER", from);
        }

        @Test
        @DisplayName("unknown provider is rejected naming KEEVO_WHATSAPP_PROVIDER")
        void unknownProviderRejected() {
            assertRejects(builder().provider("whatsapp-cloud").build(), "KEEVO_WHATSAPP_PROVIDER", null);
        }

        @Test
        @DisplayName("wassender passes with real URL + token")
        void wassenderValid() {
            assertThatCode(builder().provider("wassender").wassender(WASSENDER_URL, WASSENDER_TOKEN).build()::validate)
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "wassender token blank/placeholder [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"your_wassender_api_token_here", "CHANGE_ME"})
        void wassenderTokenInvalid(String token) {
            assertRejects(builder().provider("wassender").wassender(WASSENDER_URL, token).build(),
                    "WASSENDER_API_TOKEN", token);
        }

        @ParameterizedTest(name = "wassender URL blank/placeholder [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"https://your_wassender_host"})
        void wassenderUrlInvalid(String url) {
            assertRejects(builder().provider("wassender").wassender(url, WASSENDER_TOKEN).build(),
                    "WASSENDER_API_URL", url);
        }

        @Test
        @DisplayName("twilio passes with real SID + token")
        void twilioValid() {
            assertThatCode(builder().provider("twilio").twilio(TWILIO_SID, TWILIO_TOKEN).build()::validate)
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "twilio SID blank/placeholder [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "your_account_sid"})
        void twilioSidInvalid(String sid) {
            assertRejects(builder().provider("twilio").twilio(sid, TWILIO_TOKEN).build(),
                    "TWILIO_ACCOUNT_SID", sid);
        }

        @ParameterizedTest(name = "twilio auth token blank/placeholder [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"your_twilio_auth_token_here"})
        void twilioTokenInvalid(String token) {
            assertRejects(builder().provider("twilio").twilio(TWILIO_SID, token).build(),
                    "TWILIO_AUTH_TOKEN", token);
        }

        @Test
        @DisplayName("failover passes when both providers are configured")
        void failoverValid() {
            assertThatCode(builder().provider("failover")
                    .wassender(WASSENDER_URL, WASSENDER_TOKEN).twilio(TWILIO_SID, TWILIO_TOKEN).build()::validate)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("failover rejects a missing Twilio token even if Wassender is valid")
        void failoverRejectsTwilio() {
            assertRejects(builder().provider("failover")
                    .wassender(WASSENDER_URL, WASSENDER_TOKEN).twilio(TWILIO_SID, "").build(),
                    "TWILIO_AUTH_TOKEN", null);
        }

        @Test
        @DisplayName("failover rejects a placeholder Wassender token even if Twilio is valid")
        void failoverRejectsWassender() {
            assertRejects(builder().provider("failover")
                    .wassender(WASSENDER_URL, "your_wassender_api_token_here").twilio(TWILIO_SID, TWILIO_TOKEN).build(),
                    "WASSENDER_API_TOKEN", "your_wassender_api_token_here");
        }
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path — all secrets properly provided")
    class HappyPath {

        @Test
        @DisplayName("passes when all production secrets are valid")
        void passesWhenAllSecretsAreValid() {
            var validator = validator("Str0ngPr0dP@ss!", privateKey, publicKey);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "Story 11.4: missing DASHBOARD_ORIGIN only warns [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void missingDashboardOriginOnlyWarns(String origin) {
            var validator = builder().dashboardOrigin(origin).build();

            List<String> warnings = captureWarnings(() ->
                    assertThatCode(validator::validate).doesNotThrowAnyException());

            assertThat(warnings).filteredOn(m -> m.contains("DASHBOARD_ORIGIN")).hasSize(1)
                    .allSatisfy(m -> assertThat(m).doesNotContain("[FailFast]"));
        }

        @Test
        @DisplayName("Story 11.4: no DASHBOARD_ORIGIN warning when it is configured")
        void configuredDashboardOriginDoesNotWarn() {
            var validator = builder().dashboardOrigin("https://admin.keevo.cm").build();

            List<String> warnings = captureWarnings(validator::validate);

            assertThat(warnings).noneMatch(m -> m.contains("DASHBOARD_ORIGIN"));
        }
    }

    /** WARN messages logged by ProdSecretsValidator while {@code action} runs. */
    private static List<String> captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ProdSecretsValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}

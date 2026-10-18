package com.keevo.shared.infrastructure.config;

import com.keevo.shared.domain.PasswordLimits;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ProdSecretsValidator — Fail-fast guard for required production secrets.
 *
 * <p>Active only in {@code prod} and {@code staging} profiles.
 * Prevents boot if mandatory secrets are absent, empty, defaulted, placeholders or weak.
 * Error messages name the offending environment variable and never echo the secret value.
 *
 * <p>Checks performed:
 * <ul>
 *   <li>{@code POSTGRES_PASSWORD} ({@code spring.datasource.password}) — WARN only (never fails):
 *       blank, a known dev/CI default, a placeholder or shorter than {@value #MIN_SECRET_LENGTH}
 *       characters. The live database already uses it and changing it means {@code ALTER USER}
 *       first, so a server update must not stop on it.</li>
 *   <li>{@code ADMIN_PASSWORD} — not blank, not a known default ({@code Admin@1234!}) nor a
 *       placeholder, at least {@value #MIN_SECRET_LENGTH} characters, at least 3 of 4 character
 *       classes (lower/upper/digit/symbol), at most 72 UTF-8 bytes (BCrypt limit)</li>
 *   <li>{@code KEEVO_JWT_PRIVATE_KEY_PATH} / {@code KEEVO_JWT_PUBLIC_KEY_PATH} — absolute,
 *       readable, non-empty files (not {@code classpath:}); private key not world-readable</li>
 *   <li>{@code KEEVO_WHATSAPP_PROVIDER} — the tokens of the selected provider are present and
 *       not placeholders ({@code wassender}: URL + token; {@code twilio}: SID + auth token;
 *       {@code failover}: both). Twilio also requires {@code TWILIO_FROM_NUMBER}. {@code noop}/unset stays legal and only logs a WARN.</li>
 *   <li>{@code DASHBOARD_ORIGIN} — WARN only (never fails): when unset, CORS allows no browser
 *       origin in prod/staging (Story 11.4 AC5).</li>
 * </ul>
 *
 * <p>{@link ProdSecretsValidatorOrdering} makes every {@code DataSource} depend on this bean, so
 * the validation runs before any DB connection (Hikari/Flyway).
 *
 * <p>Story 10.3 — AC1, AC2, AC3. Story v1s-12-13 — DB password, provider tokens, admin policy.
 *
 * <p>Architecture: shared infrastructure config — no business logic.
 * Strategy pattern: this bean is the "strict" strategy for prod/staging;
 * dev/test profiles have no validator (permissive by absence).
 */
@Component
@Profile({"prod", "staging"})
public class ProdSecretsValidator {

    private static final Logger log = LoggerFactory.getLogger(ProdSecretsValidator.class);

    /** Minimum length for the DB and admin passwords. */
    static final int MIN_SECRET_LENGTH = 12;

    /** Known dev / CI / example defaults — compared case-insensitively. */
    private static final Set<String> KNOWN_DEFAULTS = Set.of(
            "admin@1234!",
            "keevo_local_pwd",
            "change_me_in_dot_env",
            "change_me_strong_password",
            "ci_password",
            "postgres",
            "keevo",
            "password");

    /** Placeholder markers (case-insensitive substring) — a value containing one is treated as unset. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change_me", "changeme", "your_", "replace_with", "placeholder", "xxxxxxxx");

    private static final String PROVIDER_NOOP = "noop";
    private static final String PROVIDER_WASSENDER = "wassender";
    private static final String PROVIDER_TWILIO = "twilio";
    private static final String PROVIDER_FAILOVER = "failover";

    private final AdminProperties adminProperties;
    private final String jwtPrivateKeyPath;
    private final String jwtPublicKeyPath;
    private final String dbPassword;
    private final String whatsappProvider;
    private final String wassenderApiUrl;
    private final String wassenderApiToken;
    private final String twilioAccountSid;
    private final String twilioAuthToken;
    private final String twilioFromNumber;
    private final String dashboardOrigin;

    public ProdSecretsValidator(
            AdminProperties adminProperties,
            @Value("${keevo.jwt.private-key-path}") String jwtPrivateKeyPath,
            @Value("${keevo.jwt.public-key-path}") String jwtPublicKeyPath,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${keevo.whatsapp.provider:noop}") String whatsappProvider,
            @Value("${keevo.whatsapp.wassender.api-url:}") String wassenderApiUrl,
            @Value("${keevo.whatsapp.wassender.api-token:}") String wassenderApiToken,
            @Value("${keevo.whatsapp.twilio.account-sid:}") String twilioAccountSid,
            @Value("${keevo.whatsapp.twilio.auth-token:}") String twilioAuthToken,
            @Value("${keevo.whatsapp.twilio.from-number:}") String twilioFromNumber,
            @Value("${DASHBOARD_ORIGIN:}") String dashboardOrigin) {
        this.adminProperties = adminProperties;
        this.jwtPrivateKeyPath = jwtPrivateKeyPath;
        this.jwtPublicKeyPath = jwtPublicKeyPath;
        this.dbPassword = dbPassword;
        this.whatsappProvider = whatsappProvider;
        this.wassenderApiUrl = wassenderApiUrl;
        this.wassenderApiToken = wassenderApiToken;
        this.twilioAccountSid = twilioAccountSid;
        this.twilioAuthToken = twilioAuthToken;
        this.twilioFromNumber = twilioFromNumber;
        this.dashboardOrigin = dashboardOrigin;
    }

    @PostConstruct
    public void validate() {
        log.info("[FailFast] Validating production secrets...");
        validateDatabasePassword();
        validateAdminPassword();
        validateJwtKeys();
        validateWhatsAppProvider();
        checkDashboardOrigin();
        log.info("[FailFast] Production secrets validation passed.");
    }

    /** Story 11.4 AC5 — warn (not fail): without DASHBOARD_ORIGIN no browser origin passes CORS. */
    private void checkDashboardOrigin() {
        if (dashboardOrigin == null || dashboardOrigin.isBlank()) {
            log.warn("[CORS] DASHBOARD_ORIGIN is not set — CORS will refuse every browser origin. "
                    + "Set it to the dashboard URL(s), comma-separated, if a browser calls the API directly.");
        } else {
            log.info("[CORS] DASHBOARD_ORIGIN configured");
        }
    }

    /** WARN only: the running database already uses this password (see class doc). */
    private void validateDatabasePassword() {
        if (isPlaceholderOrDefault(dbPassword)) {
            log.warn("[Security] POSTGRES_PASSWORD is missing or uses a dev/placeholder default. "
                    + "Rotate it when convenient: ALTER USER on the database first, then .env "
                    + "(e.g. openssl rand -hex 16).");
        } else if (dbPassword.strip().length() < MIN_SECRET_LENGTH) {
            log.warn("[Security] POSTGRES_PASSWORD is shorter than " + MIN_SECRET_LENGTH
                    + " characters. Rotate it when convenient: ALTER USER on the database first, then .env.");
        } else {
            log.info("[FailFast] POSTGRES_PASSWORD ✓");
        }
    }

    private void validateAdminPassword() {
        String pwd = adminProperties.password();
        if (isPlaceholderOrDefault(pwd)) {
            throw new IllegalStateException(
                    "[FailFast] ADMIN_PASSWORD is required in production — do not use the default or placeholder value. "
                            + "Set the ADMIN_PASSWORD environment variable to a strong, unique password.");
        }
        if (PasswordLimits.exceedsBcryptLimit(pwd)) {
            throw new IllegalStateException(
                    "[FailFast] ADMIN_PASSWORD exceeds " + PasswordLimits.MAX_BCRYPT_BYTES
                            + " UTF-8 bytes (BCrypt limit) — choose a shorter password.");
        }
        if (pwd.strip().length() < MIN_SECRET_LENGTH || characterClassCount(pwd) < 3) {
            throw new IllegalStateException(
                    "[FailFast] ADMIN_PASSWORD is too weak — at least " + MIN_SECRET_LENGTH
                            + " characters and 3 of 4 character classes (lowercase, uppercase, digit, symbol) are required.");
        }
        log.info("[FailFast] ADMIN_PASSWORD ✓");
    }

    private void validateJwtKeys() {
        validateJwtKey("KEEVO_JWT_PRIVATE_KEY_PATH", jwtPrivateKeyPath);
        validateJwtKey("KEEVO_JWT_PUBLIC_KEY_PATH", jwtPublicKeyPath);
        validatePrivateKeyNotWorldReadable("KEEVO_JWT_PRIVATE_KEY_PATH", jwtPrivateKeyPath);
        log.info("[FailFast] JWT key paths ✓");
    }

    private void validateWhatsAppProvider() {
        // No trim: the adapters' @ConditionalOnProperty compare the raw value (case-insensitive),
        // so an empty or padded value would leave no WhatsAppPort adapter — reject it here.
        String provider = whatsappProvider == null ? PROVIDER_NOOP : whatsappProvider.toLowerCase(Locale.ROOT);
        switch (provider) {
            case PROVIDER_NOOP -> log.warn("[FailFast] KEEVO_WHATSAPP_PROVIDER is noop — "
                    + "WhatsApp messages (OTP, notifications) will NOT be delivered in this environment.");
            case PROVIDER_WASSENDER -> validateWassender();
            case PROVIDER_TWILIO -> validateTwilio();
            case PROVIDER_FAILOVER -> {
                validateWassender();
                validateTwilio();
            }
            default -> throw new IllegalStateException(
                    "[FailFast] KEEVO_WHATSAPP_PROVIDER has an unknown value — expected one of "
                            + "noop, wassender, twilio, failover.");
        }
        log.info("[FailFast] WhatsApp provider configuration ✓");
    }

    private void validateWassender() {
        requireProviderValue("WASSENDER_API_URL", wassenderApiUrl);
        requireProviderValue("WASSENDER_API_TOKEN", wassenderApiToken);
    }

    private void validateTwilio() {
        requireProviderValue("TWILIO_ACCOUNT_SID", twilioAccountSid);
        requireProviderValue("TWILIO_AUTH_TOKEN", twilioAuthToken);
        requireProviderValue("TWILIO_FROM_NUMBER", twilioFromNumber);
    }

    private static void requireProviderValue(String envVar, String value) {
        if (isBlankOrPlaceholder(value)) {
            throw new IllegalStateException(
                    "[FailFast] " + envVar + " is missing or a placeholder, but the selected "
                            + "KEEVO_WHATSAPP_PROVIDER requires it. Set " + envVar + " to the real value.");
        }
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

    /** Blank, contains a placeholder marker, or equals a known dev/CI/example default (case-insensitive). */
    static boolean isPlaceholderOrDefault(String value) {
        return isBlankOrPlaceholder(value)
                || KNOWN_DEFAULTS.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlankOrPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }

    private static int characterClassCount(String pwd) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < pwd.length(); ) {
            int cp = pwd.codePointAt(i);
            if (Character.isLowerCase(cp)) {
                lower = true;
            } else if (Character.isUpperCase(cp)) {
                upper = true;
            } else if (Character.isDigit(cp)) {
                digit = true;
            } else if (!Character.isWhitespace(cp)) {
                symbol = true;
            }
            i += Character.charCount(cp);
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
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

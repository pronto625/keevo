package com.keevo.shared.infrastructure.persistence;

import java.util.regex.Pattern;

/**
 * Centralized tenant schema name validation (ARCH18).
 *
 * <p>The tenant schema name format {@code kv_[a-z0-9]{6}} is the trust gate for any
 * raw-JDBC tenant-qualified query. This class centralizes the regex previously
 * duplicated across:
 * <ul>
 *   <li>{@link SchemaAwareMultiTenantConnectionProvider#isTenantSchema(String)} — {@code search_path} gate</li>
 *   <li>{@link TenantSchemaSyncService#syncIfNeeded(String)} — no-op on invalid</li>
 *   <li>{@link TenantSchemaProvisioner#provision(String, String)} — throw on invalid</li>
 *   <li>{@link TenantSchemaProvisioner#dropSchemaIfExists(String)} — no-op on invalid</li>
 * </ul>
 *
 * <p><b>Format:</b> {@code ^kv_[a-z0-9]{6}$} — exactly 6 lowercase alphanumeric characters
 * after the {@code kv_} prefix. This is the PostgreSQL schema name, <b>not</b> the
 * public tenant code ({@code KV-XXXXXX}).
 *
 * <p><b>Thread-safety:</b> {@link Pattern} is inherently thread-safe; this class is
 * safe for concurrent use without external synchronization.
 *
 * @see TenantSchemaProvisioner
 * @see SchemaAwareMultiTenantConnectionProvider
 */
public final class TenantSchema {

    /**
     * Compiled regex for tenant schema name validation.
     * Format: {@code kv_} + exactly 6 lowercase alphanumeric characters.
     */
    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^kv_[a-z0-9]{6}$");

    private TenantSchema() {
        // Utility class — prevent instantiation
    }

    /**
     * Returns {@code true} if {@code schemaName} matches the tenant schema format.
     *
     * @param schemaName the schema name to check (may be {@code null})
     * @return {@code true} if the schema name is valid
     */
    public static boolean isValid(String schemaName) {
        return schemaName != null && SCHEMA_PATTERN.matcher(schemaName).matches();
    }

    /**
     * Validates the given schema name and returns it unchanged if valid.
     *
     * <p>Contract mirrors {@link TenantSchemaProvisioner#provision(String, String)}
     * (throw on invalid format), but uses {@link IllegalArgumentException} (runtime,
     * uncatched by {@code catch (JwtException)} in {@code JwtAuthFilter}) so the
     * caller can produce a 401 {@code TOKEN_INVALID} instead of a 500 server error.
     *
     * @param schemaName the schema name to validate
     * @return the validated schema name (identical to input)
     * @throws IllegalArgumentException if {@code schemaName} is {@code null} or
     *                                  does not match {@code ^kv_[a-z0-9]{6}$}
     */
    public static String validate(String schemaName) {
        if (!isValid(schemaName)) {
            throw new IllegalArgumentException("Invalid tenant schema name format: " + schemaName);
        }
        return schemaName;
    }
}
package com.keevo.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AdminProperties — Typed configuration for the SUPER_ADMIN bootstrap account.
 *
 * <p>Values are resolved from {@code application.yml} (prefix {@code keevo.admin})
 * with environment-variable overrides:
 * <ul>
 *   <li>{@code ADMIN_PHONE} — phone number for the super-admin account</li>
 *   <li>{@code ADMIN_PASSWORD} — plain-text password (hashed before storage)</li>
 * </ul>
 *
 * <p>Architecture: shared infrastructure config — no business logic.
 */
@ConfigurationProperties(prefix = "keevo.admin")
public record AdminProperties(String phone, String password) {}

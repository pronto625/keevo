package com.keevo.shared.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JwtProperties — Binds {@code keevo.jwt.*} YAML properties.
 *
 * <p>Story 16.11 — long and safe sessions: a short access token (30 min) renewed silently by the
 * refresh flow; a refresh token valid 365 days from its last use (each rotation resets it); a
 * shorter sliding session for the super-admin (12 h). {@code legacyClaims} keeps the indicative
 * V1 claims ({@code role}, {@code tenantStatus}, {@code storeId}, {@code passwordChangeRequired},
 * {@code onboardingCompleted}) in access tokens for installed V1 apps until the minimum app version
 * is forced (Story 16.7); the server never reads them for authorization.
 */
@ConfigurationProperties(prefix = "keevo.jwt")
public class JwtProperties {

    private String privateKeyPath;
    private String publicKeyPath;
    private int accessTokenExpiryMinutes = 30;
    private int refreshTokenExpiryDays = 365;
    private int superAdminRefreshExpiryHours = 12;
    private boolean legacyClaims = true;

    public JwtProperties() {}

    /** Test-only constructor (used without Spring context). */
    public JwtProperties(int accessTokenExpiryMinutes, int refreshTokenExpiryDays) {
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    public String getPrivateKeyPath()           { return privateKeyPath; }
    public void setPrivateKeyPath(String v)     { this.privateKeyPath = v; }

    public String getPublicKeyPath()            { return publicKeyPath; }
    public void setPublicKeyPath(String v)      { this.publicKeyPath = v; }

    public int getAccessTokenExpiryMinutes()       { return accessTokenExpiryMinutes; }
    public void setAccessTokenExpiryMinutes(int v) { this.accessTokenExpiryMinutes = v; }

    /** Access token lifetime in seconds (the {@code expiresIn} of a login / refresh answer). */
    public long getAccessTokenExpirySeconds()   { return accessTokenExpiryMinutes * 60L; }

    public int getRefreshTokenExpiryDays()      { return refreshTokenExpiryDays; }
    public void setRefreshTokenExpiryDays(int v){ this.refreshTokenExpiryDays = v; }

    public int getSuperAdminRefreshExpiryHours()       { return superAdminRefreshExpiryHours; }
    public void setSuperAdminRefreshExpiryHours(int v) { this.superAdminRefreshExpiryHours = v; }

    public boolean isLegacyClaims()             { return legacyClaims; }
    public void setLegacyClaims(boolean v)      { this.legacyClaims = v; }
}

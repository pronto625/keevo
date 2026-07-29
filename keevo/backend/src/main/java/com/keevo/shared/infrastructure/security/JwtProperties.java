package com.keevo.shared.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JwtProperties — Binds {@code keevo.jwt.*} YAML properties.
 *
 * <p>Loaded by Spring Boot's @ConfigurationProperties scanning.
 */
@ConfigurationProperties(prefix = "keevo.jwt")
public class JwtProperties {

    private String privateKeyPath;
    private String publicKeyPath;
    private int accessTokenExpiryHours = 1440; // 60 days
    private int refreshTokenExpiryDays = 360;

    public JwtProperties() {}

    /** Test-only constructor (used without Spring context). */
    public JwtProperties(int accessTokenExpiryHours, int refreshTokenExpiryDays) {
        this.accessTokenExpiryHours = accessTokenExpiryHours;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    public String getPrivateKeyPath()           { return privateKeyPath; }
    public void setPrivateKeyPath(String v)     { this.privateKeyPath = v; }

    public String getPublicKeyPath()            { return publicKeyPath; }
    public void setPublicKeyPath(String v)      { this.publicKeyPath = v; }

    public int getAccessTokenExpiryHours()      { return accessTokenExpiryHours; }
    public void setAccessTokenExpiryHours(int v){ this.accessTokenExpiryHours = v; }

    public int getRefreshTokenExpiryDays()      { return refreshTokenExpiryDays; }
    public void setRefreshTokenExpiryDays(int v){ this.refreshTokenExpiryDays = v; }
}

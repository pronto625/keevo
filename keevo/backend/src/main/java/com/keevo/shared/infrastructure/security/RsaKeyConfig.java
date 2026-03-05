package com.keevo.shared.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RsaKeyConfig — Loads RSA 2048-bit key pair from PEM files.
 *
 * <p>Keys are loaded once at startup and injected into {@link JwtTokenProvider}.
 * Production keys are gitignored and injected via environment/secrets manager.
 * Test keys live in src/test/resources/keys/ (weak — for tests only).
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class RsaKeyConfig {

    @Bean
    public RSAPrivateKey rsaPrivateKey(
            @Value("${keevo.jwt.private-key-path}") Resource path) throws Exception {
        String pem = new String(path.getInputStream().readAllBytes())
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    @Bean
    public RSAPublicKey rsaPublicKey(
            @Value("${keevo.jwt.public-key-path}") Resource path) throws Exception {
        String pem = new String(path.getInputStream().readAllBytes())
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }
}

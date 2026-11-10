package com.keevo.identity.auth.adapter.out.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Story 19.3b — chooses the {@link AppleTokenClient} adapter. Disabled by default ({@link DisabledAppleTokenClient}).
 * With {@code keevo.auth.social.apple.revocation.enabled=true} ({@code APPLE_REVOCATION_ENABLED}) the HTTP adapter is
 * built from {@code team-id} ({@code APPLE_TEAM_ID}), {@code key-id} ({@code APPLE_KEY_ID}), {@code private-key}
 * ({@code APPLE_PRIVATE_KEY}: the PKCS#8 PEM of the {@code .p8} key, literal {@code \n} accepted) and the client ids
 * allowed ({@code APPLE_REVOCATION_CLIENT_IDS}, default the Apple sign-in audiences). Enabled with a missing or
 * invalid value, or without {@code KEEVO_SECRETS_ENCRYPTION_KEY} (tokens are stored encrypted) → startup fails.
 */
@Configuration
public class AppleRevocationConfig {

    private static final Logger log = LoggerFactory.getLogger(AppleRevocationConfig.class);

    static final String PREFIX = "keevo.auth.social.apple.revocation.";
    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    public AppleTokenClient appleTokenClient(Environment env, SecretCipher cipher, ObjectMapper objectMapper,
                                             Clock clock) {
        return create(env, cipher, objectMapper, clock);
    }

    static AppleTokenClient create(Environment env, SecretCipher cipher, ObjectMapper objectMapper, Clock clock) {
        if (!env.getProperty(PREFIX + "enabled", Boolean.class, false)) {
            log.info("[social] Apple token revocation disabled");
            return new DisabledAppleTokenClient();
        }
        String teamId = required(env, "team-id");
        String keyId = required(env, "key-id");
        PrivateKey key = parsePrivateKey(required(env, "private-key"));
        Set<String> clientIds = new LinkedHashSet<>(SocialAuthConfig.commaList(env.getProperty(PREFIX + "client-ids")));
        if (clientIds.isEmpty()) {
            List<String> audiences = SocialAuthConfig.commaList(env.getProperty("keevo.auth.social.apple.audiences"));
            clientIds.addAll(audiences.isEmpty()
                    ? SocialAuthConfig.commaList(env.getProperty("keevo.auth.social.apple.bundle-id"))
                    : audiences);
        }
        if (clientIds.isEmpty()) {
            throw new IllegalStateException(PREFIX + "client-ids (APPLE_REVOCATION_CLIENT_IDS) or APPLE_BUNDLE_IDS "
                    + "is required when Apple token revocation is enabled");
        }
        if (!cipher.isAvailable()) {
            throw new IllegalStateException("KEEVO_SECRETS_ENCRYPTION_KEY is required when Apple token revocation "
                    + "is enabled (tokens are stored encrypted)");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        log.info("[social] Apple token revocation enabled for {} client id(s)", clientIds.size());
        return new HttpAppleTokenClient(new RestTemplate(factory), objectMapper, teamId, keyId, key, clientIds, clock);
    }

    private static String required(Environment env, String name) {
        String value = env.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PREFIX + name + " is required when Apple token revocation is enabled");
        }
        return value.strip();
    }

    /** PKCS#8 PEM (or bare base64) of an EC P-256 key; literal {@code \n} sequences accepted (one-line env values). */
    static PrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            if (!(key instanceof ECPrivateKey)) {
                throw new IllegalStateException("not an EC key");
            }
            return key;
        } catch (Exception e) {
            // Never echo the value
            throw new IllegalStateException(PREFIX + "private-key (APPLE_PRIVATE_KEY) is not a PKCS#8 EC private key");
        }
    }
}

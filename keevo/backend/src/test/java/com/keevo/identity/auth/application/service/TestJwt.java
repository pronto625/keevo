package com.keevo.identity.auth.application.service;

import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Real RS256 provider on the test key pair, so tests can decode the claims they issue. */
final class TestJwt {

    private TestJwt() {}

    static JwtTokenProvider provider(JwtProperties props) {
        try {
            return new JwtTokenProvider(privateKey(), publicKey(), props);
        } catch (Exception e) {
            throw new IllegalStateException("test keys unavailable", e);
        }
    }

    private static RSAPrivateKey privateKey() throws Exception {
        byte[] der = pem("/keys/private_key.pem", "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey publicKey() throws Exception {
        byte[] der = pem("/keys/public_key.pem", "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] pem(String resource, String label) throws Exception {
        try (var in = TestJwt.class.getResourceAsStream(resource)) {
            String body = new String(in.readAllBytes())
                    .replace("-----BEGIN " + label + "-----", "")
                    .replace("-----END " + label + "-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(body);
        }
    }
}

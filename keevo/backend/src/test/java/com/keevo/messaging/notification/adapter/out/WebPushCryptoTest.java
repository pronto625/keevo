package com.keevo.messaging.notification.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9b — RFC 8291 Appendix A test vector and an RFC 8292 VAPID token checked with the public key. */
@DisplayName("WebPushCrypto — aes128gcm (RFC 8291) and VAPID (RFC 8292), JDK only (Story 19.9b)")
class WebPushCryptoTest {

    // RFC 8291, Appendix A
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String CIPHERTEXT = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6"
            + "e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private static final Base64.Decoder D = Base64.getUrlDecoder();

    /** Receiver side of RFC 8291 (what a browser does), written independently of the sender code. */
    private static byte[] decrypt(byte[] message, String uaPrivate, String uaPublic, String auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(message);
        byte[] salt = new byte[16];
        in.get(salt);
        int recordSize = in.getInt();
        assertThat(recordSize).isEqualTo(4096);
        byte[] asPublic = new byte[in.get() & 0xff];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        ECPrivateKey uaKey = WebPushCrypto.privateKey(D.decode(uaPrivate));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaKey);
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] ecdh = agreement.generateSecret();
        byte[] keyInfo = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                D.decode(uaPublic), asPublic);
        byte[] ikm = WebPushCrypto.hkdf(D.decode(auth), ecdh, keyInfo, 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        assertThat(padded[end]).isEqualTo((byte) 2); // last-record delimiter
        return Arrays.copyOf(padded, end);
    }

    @Test
    @DisplayName("the RFC 8291 vector decrypts with the vector's receiver keys")
    void vectorDecrypts() throws Exception {
        assertThat(new String(decrypt(D.decode(CIPHERTEXT), UA_PRIVATE, UA_PUBLIC, AUTH), StandardCharsets.UTF_8))
                .isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("with the vector's sender key and salt, the output is the vector's, byte for byte")
    void vectorReproduced() throws Exception {
        KeyPair as = new KeyPair(WebPushCrypto.publicKey(D.decode(AS_PUBLIC)),
                WebPushCrypto.privateKey(D.decode(AS_PRIVATE)));
        byte[] out = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), D.decode(UA_PUBLIC),
                D.decode(AUTH), as, D.decode(SALT));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(out)).isEqualTo(CIPHERTEXT);
    }

    @Test
    @DisplayName("a random-key encryption decrypts with the receiver keys (padding optional in the key strings)")
    void randomRoundTrip() throws Exception {
        byte[] out = WebPushCrypto.encrypt("{\"title\":\"Stock bas\"}".getBytes(StandardCharsets.UTF_8),
                UA_PUBLIC + "=", AUTH + "==");
        assertThat(new String(decrypt(out, UA_PRIVATE, UA_PUBLIC, AUTH), StandardCharsets.UTF_8))
                .isEqualTo("{\"title\":\"Stock bas\"}");
    }

    @Test
    @DisplayName("VAPID: ES256 JWT (aud, exp = now + 12 h, sub) verified with the public key; header vapid t=…, k=…")
    void vapid() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(WebPushCrypto.p256Spec());
        KeyPair pair = generator.generateKeyPair();
        String publicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.encodePoint((ECPublicKey) pair.getPublic()));
        byte[] scalar = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        byte[] fixed = new byte[32];
        System.arraycopy(scalar, Math.max(0, scalar.length - 32), fixed, Math.max(0, 32 - scalar.length),
                Math.min(32, scalar.length));
        String privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
        assertThat(WebPushCrypto.isValidKeyPair(publicKey, privateKey)).isTrue();

        String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com", "mailto:admin@brefpoint.app",
                publicKey, privateKey, 1_000_000L);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + publicKey);
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode head = new ObjectMapper().readTree(D.decode(parts[0]));
        JsonNode claims = new ObjectMapper().readTree(D.decode(parts[1]));
        assertThat(head.path("alg").asText()).isEqualTo("ES256");
        assertThat(claims.path("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.path("exp").asLong()).isEqualTo(1_000_000L + 12 * 3600);
        assertThat(claims.path("sub").asText()).isEqualTo("mailto:admin@brefpoint.app");

        byte[] signature = D.decode(parts[2]);
        assertThat(signature).hasSize(64);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(WebPushCrypto.publicKey(D.decode(publicKey)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    @DisplayName("invalid keys are refused")
    void invalidKeys() {
        assertThat(WebPushCrypto.isValidKeyPair("abc", "def")).isFalse();
        assertThat(WebPushCrypto.isValidKeyPair(UA_PUBLIC, "")).isFalse();
    }
}

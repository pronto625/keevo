package com.keevo.messaging.notification.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Story 19.9b — Web Push cryptography with the JDK only (no new dependency, offline build):
 *
 * <ul>
 *   <li>RFC 8291 message encryption, {@code aes128gcm} content coding (RFC 8188): ephemeral ECDH P-256 with the
 *       browser's {@code p256dh} key, HKDF-SHA256 with its {@code auth} secret, AES-128-GCM, one record of
 *       {@value #RECORD_SIZE} bytes;</li>
 *   <li>RFC 8292 VAPID: an ES256 JWT ({@code aud} = push service origin, {@code exp} = now + 12 h, {@code sub}), the
 *       JDK DER signature converted to raw {@code R‖S}; header {@code Authorization: vapid t=<jwt>, k=<public key>}.</li>
 * </ul>
 *
 * Keys are base64url (padding optional): public keys are uncompressed points (65 bytes), the private key the 32-byte
 * scalar, as the {@code web-push} library of Bref generates them. Stateless and thread-safe.
 */
public final class WebPushCrypto {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final int RECORD_SIZE = 4096;
    static final long VAPID_TTL_SECONDS = 12 * 3600L;

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    // ── RFC 8291 ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Encrypts {@code plaintext} for one subscription with a fresh ephemeral key and salt. */
    public static byte[] encrypt(byte[] plaintext, String p256dh, String auth) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(P256, RANDOM);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, decode(p256dh), decode(auth), generator.generateKeyPair(), salt);
    }

    /** Deterministic form (test vector): given ephemeral key pair and salt. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (authSecret.length != 16) throw new GeneralSecurityException("auth secret must be 16 bytes");
        ECPublicKey uaKey = publicKey(uaPublic);
        byte[] asPublic = encodePoint((ECPublicKey) asKeys.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asKeys.getPrivate());
        agreement.doPhase(uaKey, true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2})); // last-record delimiter, no padding

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    /** HKDF-SHA256 (RFC 5869), output of at most 32 bytes (one expand block). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ── RFC 8292 ────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code vapid t=<jwt>, k=<public key>} for this push service origin. */
    public static String vapidAuthorization(String audience, String subject, String publicKey, String privateKey,
                                            long nowEpochSeconds) throws GeneralSecurityException {
        return "vapid t=" + vapidJwt(audience, subject, privateKey, nowEpochSeconds) + ", k="
                + B64URL.encodeToString(decode(publicKey));
    }

    static String vapidJwt(String audience, String subject, String privateKey, long nowEpochSeconds)
            throws GeneralSecurityException {
        String header = B64URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        ObjectNode claimSet = JSON.createObjectNode()
                .put("aud", audience)
                .put("exp", nowEpochSeconds + VAPID_TTL_SECONDS)
                .put("sub", subject);
        String claims = B64URL.encodeToString(claimSet.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey(decode(privateKey)));
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64URL.encodeToString(derToRaw(signer.sign()));
    }

    /** ASN.1 {@code SEQUENCE {INTEGER r, INTEGER s}} → 64 bytes {@code r‖s} (JWS ES256). */
    static byte[] derToRaw(byte[] der) throws GeneralSecurityException {
        int offset = 2;
        if (der.length < 8 || der[0] != 0x30) throw new GeneralSecurityException("not a DER ECDSA signature");
        if ((der[1] & 0x80) != 0) offset += der[1] & 0x7f; // long-form length (not produced for P-256, kept safe)
        byte[] raw = new byte[64];
        for (int part = 0; part < 2; part++) {
            if (der[offset] != 0x02) throw new GeneralSecurityException("not a DER ECDSA signature");
            int length = der[offset + 1];
            int start = offset + 2;
            int skip = Math.max(0, length - 32);         // leading 0x00 of a positive INTEGER
            int copy = length - skip;
            System.arraycopy(der, start + skip, raw, part * 32 + (32 - copy), copy);
            offset = start + length;
        }
        return raw;
    }

    // ── keys ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** True when the pair is usable (boot check, without logging the keys). */
    public static boolean isValidKeyPair(String publicKey, String privateKey) {
        try {
            publicKey(decode(publicKey));
            privateKey(decode(privateKey));
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("public key must be an uncompressed P-256 point");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != 32) throw new GeneralSecurityException("private key must be a 32-byte P-256 scalar");
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
    }

    static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        writeFixed(key.getW().getAffineX(), out, 1);
        writeFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECParameterSpec p256Spec() {
        return P256;
    }

    private static void writeFixed(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int skip = Math.max(0, bytes.length - 32);
        int copy = bytes.length - skip;
        System.arraycopy(bytes, skip, out, offset + 32 - copy, copy);
    }

    /** Base64url or standard base64, padding optional. */
    static byte[] decode(String value) {
        String v = value == null ? "" : value.strip().replace('+', '-').replace('/', '_');
        while (v.endsWith("=")) v = v.substring(0, v.length() - 1);
        return Base64.getUrlDecoder().decode(v);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 curve unavailable", e);
        }
    }
}

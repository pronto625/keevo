package com.keevo.messaging.notification.domain.model;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;

/**
 * Story 19.9b — checks a browser subscription's keys at subscribe time (RFC 8291): {@code p256dh} must be an
 * uncompressed P-256 point lying on the curve, {@code auth} a 16-byte secret, both base64url (standard base64 and
 * padding accepted). A subscription that fails here would fail on every send, so it is refused instead of stored.
 */
public final class PushKeys {

    private static final EllipticCurve CURVE = p256().getCurve();

    private PushKeys() {
    }

    public static boolean isValid(String p256dh, String auth) {
        byte[] point = decode(p256dh);
        byte[] secret = decode(auth);
        return secret != null && secret.length == 16 && point != null && isOnCurve(point);
    }

    private static boolean isOnCurve(byte[] point) {
        if (point.length != 65 || point[0] != 4) return false;
        BigInteger p = ((ECFieldFp) CURVE.getField()).getP();
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger left = y.modPow(BigInteger.TWO, p);
        BigInteger right = x.pow(3).add(CURVE.getA().multiply(x)).add(CURVE.getB()).mod(p);
        return left.equals(right);
    }

    private static byte[] decode(String value) {
        if (value == null) return null;
        String v = value.strip().replace('+', '-').replace('/', '_');
        while (v.endsWith("=")) v = v.substring(0, v.length() - 1);
        try {
            return Base64.getUrlDecoder().decode(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

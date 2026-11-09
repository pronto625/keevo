package com.keevo.payments.checkout.adapter.out.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MyCoolPaySignature — md5 callback signature, as Bref computes it (Story 21.1)")
class MyCoolPaySignatureTest {

    private static Map<String, Object> callback() {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction_ref", "MCP-123");
        p.put("transaction_type", "PAYIN");
        p.put("transaction_amount", 3500);
        p.put("transaction_currency", "XAF");
        p.put("transaction_operator", "CM_MOMO");
        p.put("transaction_status", "SUCCESS");
        return p;
    }

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("md5(ref + type + amount + currency + operator + key), amount printed like JavaScript")
    void sign() throws Exception {
        String expected = md5("MCP-123PAYIN3500XAFCM_MOMOsecret");
        assertThat(MyCoolPaySignature.sign(callback(), "secret")).isEqualTo(expected);
        Map<String, Object> decimal = callback();
        decimal.put("transaction_amount", 3500.0);
        assertThat(MyCoolPaySignature.sign(decimal, "secret")).isEqualTo(expected);
        decimal.put("transaction_amount", 3500.5);
        assertThat(MyCoolPaySignature.sign(decimal, "secret")).isEqualTo(md5("MCP-123PAYIN3500.5XAFCM_MOMOsecret"));
        Map<String, Object> missing = callback();
        missing.remove("transaction_operator");
        assertThat(MyCoolPaySignature.sign(missing, "k")).isEqualTo(md5("MCP-123PAYIN3500XAFundefinedk"));
    }

    @Test
    @DisplayName("verify: right signature (any case) accepted; tampered amount, other key, no signature refused")
    void verify() {
        Map<String, Object> p = callback();
        p.put("signature", MyCoolPaySignature.sign(p, "secret").toUpperCase());
        assertThat(MyCoolPaySignature.verify(p, "secret")).isTrue();
        assertThat(MyCoolPaySignature.verify(p, "other")).isFalse();
        assertThat(MyCoolPaySignature.verify(p, "")).isFalse();
        p.put("transaction_amount", 100);
        assertThat(MyCoolPaySignature.verify(p, "secret")).isFalse();
        Map<String, Object> unsigned = callback();
        assertThat(MyCoolPaySignature.verify(unsigned, "secret")).isFalse();
        unsigned.put("signature", 42);
        assertThat(MyCoolPaySignature.verify(unsigned, "secret")).isFalse();
        assertThat(MyCoolPaySignature.verify(null, "secret")).isFalse();
    }
}

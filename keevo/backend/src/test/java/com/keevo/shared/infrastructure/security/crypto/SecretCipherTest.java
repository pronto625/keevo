package com.keevo.shared.infrastructure.security.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecretCipher — AES-256-GCM at rest, bound to its row (21.5, FUS-NFR4)")
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("round trip with the same context; random IV; another context, key or a flipped byte never opens")
    void roundTrip() {
        SecretCipher cipher = new SecretCipher(KEY);
        byte[] a = cipher.encrypt("EAAG-token", "tenant-1");
        byte[] b = cipher.encrypt("EAAG-token", "tenant-1");
        assertThat(a).isNotEqualTo(b);
        assertThat(a[0]).isEqualTo((byte) 1);
        assertThat(cipher.decrypt(a, "tenant-1")).isEqualTo("EAAG-token");
        assertThatThrownBy(() -> cipher.decrypt(a, "tenant-2")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("EAAG");
        byte[] tampered = a.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(tampered, "tenant-1")).isInstanceOf(IllegalStateException.class);
        SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]).replace('A', 'B'));
        assertThatThrownBy(() -> other.decrypt(a, "tenant-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no key: unavailable; a malformed key fails the startup")
    void keyRules() {
        SecretCipher none = new SecretCipher("");
        assertThat(none.isAvailable()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x", "c")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("not base64 !")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}

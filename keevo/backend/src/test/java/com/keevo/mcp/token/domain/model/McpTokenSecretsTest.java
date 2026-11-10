package com.keevo.mcp.token.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("21.9 — personal access token secrets: shape, randomness, SHA-256, constant-time comparison, hint")
class McpTokenSecretsTest {

    @Test
    @DisplayName("a token is kvp_ + 43 base64url characters, unique, recognised by its shape")
    void generate() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = McpTokenSecrets.generate();
            assertThat(t).startsWith("kvp_").hasSize(McpTokenSecrets.LENGTH).matches("kvp_[A-Za-z0-9_-]{43}");
            assertThat(McpTokenSecrets.looksLikeToken(t)).isTrue();
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    @DisplayName("anything else is rejected before any lookup")
    void shape() {
        assertThat(McpTokenSecrets.looksLikeToken(null)).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("")).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("kvp_short")).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("kvx_" + "a".repeat(43))).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("kvp_" + "a".repeat(42) + "=")).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("kvp_" + "a".repeat(42) + "é")).isFalse();
        assertThat(McpTokenSecrets.looksLikeToken("kvp_" + "a".repeat(44))).isFalse();
    }

    @Test
    @DisplayName("hash = SHA-256 (32 bytes, deterministic), compared in constant time; hint = last 4 characters")
    void hashAndHint() {
        String t = McpTokenSecrets.generate();
        byte[] h = McpTokenSecrets.hash(t);
        assertThat(h).hasSize(32).isEqualTo(McpTokenSecrets.hash(t));
        assertThat(McpTokenSecrets.sameHash(h, McpTokenSecrets.hash(t))).isTrue();
        assertThat(McpTokenSecrets.sameHash(h, McpTokenSecrets.hash(McpTokenSecrets.generate()))).isFalse();
        assertThat(McpTokenSecrets.sameHash(h, null)).isFalse();
        assertThat(McpTokenSecrets.hint(t)).isEqualTo(t.substring(t.length() - 4)).hasSize(4);
    }
}

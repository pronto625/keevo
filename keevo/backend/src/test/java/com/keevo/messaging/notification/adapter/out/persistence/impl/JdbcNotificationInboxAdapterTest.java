package com.keevo.messaging.notification.adapter.out.persistence.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JdbcNotificationInboxAdapter — values made storable (Story 19.9)")
class JdbcNotificationInboxAdapterTest {

    @Test
    @DisplayName("NUL characters are stripped (PostgreSQL refuses them), before truncating")
    void stripNul() {
        assertThat(JdbcNotificationInboxAdapter.stripNul("Stock\0 bas\0")).isEqualTo("Stock bas");
        assertThat(JdbcNotificationInboxAdapter.stripNul("propre")).isEqualTo("propre");
        assertThat(JdbcNotificationInboxAdapter.stripNul(null)).isNull();
        assertThat(JdbcNotificationInboxAdapter.clean("\0".repeat(5) + "a".repeat(64),
                JdbcNotificationInboxAdapter.TYPE_MAX)).isEqualTo("a".repeat(64));
        assertThat(JdbcNotificationInboxAdapter.clean(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("truncated to 64 / 150 / 1000; values at the limit kept whole")
    void truncateLimits() {
        for (int max : new int[]{JdbcNotificationInboxAdapter.TYPE_MAX, JdbcNotificationInboxAdapter.TITLE_MAX,
                JdbcNotificationInboxAdapter.BODY_MAX}) {
            assertThat(JdbcNotificationInboxAdapter.clean("x".repeat(max), max)).hasSize(max);
            assertThat(JdbcNotificationInboxAdapter.clean("x".repeat(max + 1), max)).hasSize(max);
        }
        assertThat(JdbcNotificationInboxAdapter.TYPE_MAX).isEqualTo(64);
        assertThat(JdbcNotificationInboxAdapter.TITLE_MAX).isEqualTo(150);
        assertThat(JdbcNotificationInboxAdapter.BODY_MAX).isEqualTo(1000);
    }

    @Test
    @DisplayName("a surrogate pair cut by the limit is dropped whole, never split")
    void surrogateBoundary() {
        String emoji = "📦"; // one code point, two chars
        String title = "x".repeat(149) + emoji;
        String cut = JdbcNotificationInboxAdapter.clean(title, JdbcNotificationInboxAdapter.TITLE_MAX);
        assertThat(cut).isEqualTo("x".repeat(149));
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();

        String fits = "x".repeat(148) + emoji;
        assertThat(JdbcNotificationInboxAdapter.clean(fits, JdbcNotificationInboxAdapter.TITLE_MAX)).isEqualTo(fits);
    }
}

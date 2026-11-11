package com.keevo.shared.capability.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Story 19.9c — the platform message parameter object and the port default. */
@DisplayName("PlatformWhatsAppMessage (Story 19.9c)")
class PlatformWhatsAppMessageTest {

    @Test
    @DisplayName("detail: the text is the only parameter; fixed: no parameter; of: structured parameters")
    void factories() {
        assertThat(PlatformWhatsAppMessage.detail(PlatformMessageKind.STOCK_ALERT, "Riz").parameters())
                .containsExactly("Riz");
        assertThat(PlatformWhatsAppMessage.fixed(PlatformMessageKind.ACCOUNT_DELETED, "Supprimé").parameters())
                .isEmpty();
        PlatformWhatsAppMessage member = PlatformWhatsAppMessage.of(PlatformMessageKind.MEMBER_ADDED, "texte",
                "Boutique Akwa", "Gérant");
        assertThat(member.parameters()).containsExactly("Boutique Akwa", "Gérant");
        assertThat(member.text()).isEqualTo("texte");
    }

    @Test
    @DisplayName("a parameter count different from the kind's is a programming error")
    void wrongCount() {
        assertThatThrownBy(() -> PlatformWhatsAppMessage.of(PlatformMessageKind.STOCK_ALERT, "t", "a", "b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STOCK_ALERT");
        assertThatThrownBy(() -> PlatformWhatsAppMessage.fixed(PlatformMessageKind.MEMBER_ADDED, "t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null text or values never crash: empty strings")
    void nulls() {
        PlatformWhatsAppMessage m = PlatformWhatsAppMessage.detail(PlatformMessageKind.DAILY_REPORT, null);
        assertThat(m.text()).isEmpty();
        assertThat(m.parameters()).containsExactly("");
        assertThat(PlatformWhatsAppMessage.of(PlatformMessageKind.MEMBER_ADDED, "t", null, "Gérant").parameters())
                .containsExactly("", "Gérant");
        assertThat(new PlatformWhatsAppMessage(PlatformMessageKind.SYNC_SILENCE, List.of("3"), "t").parameters())
                .containsExactly("3");
    }

    @Test
    @DisplayName("port default: sendNotification sends the free text through sendReport")
    void portDefault() {
        WhatsAppSender sender = WhatsAppSenderMocks.forwardNotifications(mock(WhatsAppSender.class));
        sender.sendNotification("+237699000080", PlatformWhatsAppMessage.detail(PlatformMessageKind.STOCK_ALERT, "Riz"));
        verify(sender).sendReport("+237699000080", "Riz");
    }
}

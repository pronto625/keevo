package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.module.ModuleScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.13 — the listeners that would run for a till / multi-store disabled by the owner (or the subscription) are
 * gated per tenant by {@code @ModuleScoped} on their class: no day-closure push or WhatsApp, no sale-draft
 * notification, no transfer notification while the module is DISABLED.
 */
@DisplayName("19.13 — @ModuleScoped placement on the pos / multi-store notification listeners")
class OwnerGatedListenersPlacementTest {

    @Test
    @DisplayName("day closure (push, WhatsApp) and sale drafts → pos; transfers → multi-store")
    void placement() {
        assertThat(DayClosureNotificationListener.class.getAnnotation(ModuleScoped.class).value()).isEqualTo("pos");
        assertThat(DayClosureWhatsAppListener.class.getAnnotation(ModuleScoped.class).value()).isEqualTo("pos");
        assertThat(SaleDraftValidatedNotificationListener.class.getAnnotation(ModuleScoped.class).value())
                .isEqualTo("pos");
        assertThat(TransferCreatedNotificationListener.class.getAnnotation(ModuleScoped.class).value())
                .isEqualTo("multi-store");
    }
}

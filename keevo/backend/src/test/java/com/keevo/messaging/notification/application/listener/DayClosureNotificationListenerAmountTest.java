package com.keevo.messaging.notification.application.listener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Device test: the closure notification read « 4,400 FCFA · Espèces: 4,400 ». */
class DayClosureNotificationListenerAmountTest {

    @Test
    void amountsUseFrenchGroupingWithAPlainSpace() {
        assertThat(DayClosureNotificationListener.amount(4400)).isEqualTo("4 400");
        assertThat(DayClosureNotificationListener.amount(1_250_000)).isEqualTo("1 250 000");
        assertThat(DayClosureNotificationListener.amount(0)).isEqualTo("0");
    }
}

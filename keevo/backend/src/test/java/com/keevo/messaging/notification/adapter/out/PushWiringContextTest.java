package com.keevo.messaging.notification.adapter.out;

import com.google.firebase.messaging.FirebaseMessaging;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoPushTicketRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.shared.capability.notification.PushNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Story 19.9 — push bean wiring: one primary PushNotifier (the decorator) over the enabled delivery channels. */
@DisplayName("Push wiring — PersistingPushNotifier over FCM + Expo (Story 19.9)")
class PushWiringContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FirebaseMessaging.class, () -> mock(FirebaseMessaging.class))
            .withBean(DeviceTokenRepository.class, () -> mock(DeviceTokenRepository.class))
            .withBean(ExpoPushTicketRepository.class, () -> mock(ExpoPushTicketRepository.class))
            .withBean(WebPushSender.class, () -> mock(WebPushSender.class))
            .withBean("notificationExecutor", java.util.concurrent.Executor.class, () -> Runnable::run)
            .withBean(NotificationInboxRepository.class, () -> mock(NotificationInboxRepository.class))
            .withBean(UserQueryPort.class, () -> mock(UserQueryPort.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean("expoRestTemplate", RestTemplate.class, RestTemplate::new)
            .withUserConfiguration(FcmNotificationAdapter.class, LoggingNotificationAdapter.class,
                    ExpoPushAdapter.class, PersistingPushNotifier.class);

    @Test
    @DisplayName("FCM and Expo enabled → the primary PushNotifier is the decorator, channels exactly FCM + Expo")
    void fcmAndExpo() {
        runner.withPropertyValues("keevo.fcm.enabled=true", "keevo.push.expo.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PushNotifier.class)).isInstanceOf(PersistingPushNotifier.class);
            assertThat(((PersistingPushNotifier) ctx.getBean(PushNotifier.class)).channels())
                    .extracting(c -> c.getClass().getSimpleName())
                    .containsExactlyInAnyOrder("FcmNotificationAdapter", "ExpoPushAdapter");
        });
    }

    @Test
    @DisplayName("defaults (both off) → the logging stub is the only channel")
    void defaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(((PersistingPushNotifier) ctx.getBean(PushNotifier.class)).channels())
                    .extracting(c -> c.getClass().getSimpleName())
                    .containsExactly("LoggingNotificationAdapter");
        });
    }
}

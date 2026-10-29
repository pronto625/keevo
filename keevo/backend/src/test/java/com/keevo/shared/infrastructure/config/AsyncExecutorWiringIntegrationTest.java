package com.keevo.shared.infrastructure.config;

import com.keevo.shared.event.catalog.DomainEvent;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story 12.4 — with scheduling ENABLED (as in production) the Boot {@code taskScheduler} is a second
 * {@code TaskExecutor}: {@code @Async} must still run on {@code notificationExecutor} (explicit
 * {@code AsyncConfigurer} default), never on {@code SimpleAsyncTaskExecutor}, and the submitter's MDC must
 * follow the task — for a bare {@code @Async} method and for a module listener.
 */
@SpringBootTest(properties = {"keevo.whatsapp.provider=noop", "keevo.scheduling.enabled=true"})
@Import(AsyncExecutorWiringIntegrationTest.ProbeConfig.class)
class AsyncExecutorWiringIntegrationTest extends PostgresIntegrationTest {

    /** What a probe saw: thread name + MDC "userId". */
    public record Seen(String thread, String mdcUserId) {}

    public record WiringProbeEvent(UUID probeId, String tenantId, Instant occurredAt, UUID actorId)
            implements DomainEvent {}

    public static class AsyncProbe {
        final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

        @Async
        public void run(UUID id) {
            seen.put(id, new Seen(Thread.currentThread().getName(), MDC.get("userId")));
        }

        @ApplicationModuleListener
        public void on(WiringProbeEvent event) {
            seen.put(event.probeId(), new Seen(Thread.currentThread().getName(), MDC.get("userId")));
        }

        public Map<UUID, Seen> seen() {
            return seen;
        }
    }

    public static class ProbePublisher {
        private final ApplicationEventPublisher publisher;

        public ProbePublisher(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void publish(Object event) {
            publisher.publishEvent(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        AsyncProbe asyncWiringProbe() {
            return new AsyncProbe();
        }

        @Bean
        ProbePublisher asyncWiringPublisher(ApplicationEventPublisher publisher) {
            return new ProbePublisher(publisher);
        }
    }

    @Autowired private ApplicationContext context;
    @Autowired private AsyncProbe probe;
    @Autowired private ProbePublisher publisher;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("scheduling on → a second TaskExecutor exists, yet @Async and module listeners run on notification-*")
    void asyncRunsOnNotificationExecutorWithMdc() {
        assertThat(context.getBeanNamesForType(TaskExecutor.class)).as("taskScheduler + notificationExecutor")
                .hasSizeGreaterThanOrEqualTo(2);

        MDC.put("userId", "u-async");
        UUID asyncId = UUID.randomUUID();
        probe.run(asyncId);

        MDC.put("userId", "u-listener");
        WiringProbeEvent event = new WiringProbeEvent(UUID.randomUUID(), "kv_wire01", Instant.now(), UUID.randomUUID());
        publisher.publish(event);

        await().atMost(Duration.ofSeconds(15)).until(() -> probe.seen().containsKey(asyncId)
                && probe.seen().containsKey(event.probeId()));
        assertThat(probe.seen().get(asyncId).thread()).startsWith("notification-");
        assertThat(probe.seen().get(asyncId).mdcUserId()).isEqualTo("u-async");
        assertThat(probe.seen().get(event.probeId()).thread()).startsWith("notification-");
        assertThat(probe.seen().get(event.probeId()).mdcUserId()).isEqualTo("u-listener");
    }
}

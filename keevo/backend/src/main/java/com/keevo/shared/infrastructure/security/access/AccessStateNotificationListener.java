package com.keevo.shared.infrastructure.security.access;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

/**
 * Story 16.11 — {@code LISTEN keevo_access_state} on a dedicated connection and evicts the local
 * {@link AccessStateService} cache on every payload, so a right removed on one instance is enforced
 * by every instance at its next request.
 *
 * <p>On connection loss everything cached is evicted (notifications may have been missed) and the
 * listener reconnects with a back-off; meanwhile the ≤ 30 s TTL bounds staleness. Disabled with
 * {@code keevo.access-state.listen-enabled=false}.
 */
@Component
public class AccessStateNotificationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccessStateNotificationListener.class);
    private static final int POLL_TIMEOUT_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final DataSource dataSource;
    private final AccessStateService accessStates;
    private final boolean enabled;
    private volatile boolean running;
    private volatile boolean listening;
    private Thread thread;

    public AccessStateNotificationListener(DataSource dataSource, AccessStateService accessStates,
                                           @Value("${keevo.access-state.listen-enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.accessStates = accessStates;
        this.enabled = enabled;
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "access-state-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(POLL_TIMEOUT_MS * 3L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        listening = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** True once {@code LISTEN} succeeded on the current connection. */
    public boolean isListening() {
        return listening;
    }

    /** Test helper: waits until the listener is connected. */
    public boolean awaitListening(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!listening && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return listening;
    }

    private void loop() {
        long backoff = 500;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + AccessStateChangePublisher.CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                // Anything cached before LISTEN may have missed a notification.
                accessStates.invalidateAll();
                listening = true;
                backoff = 500;
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_TIMEOUT_MS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            accessStates.onNotification(n.getParameter());
                        }
                    }
                }
            } catch (Throwable e) {
                // Any failure (not only exceptions) must leave the listener reconnecting, never a
                // dead thread still reporting "listening".
                listening = false;
                accessStates.invalidateAll();
                if (e instanceof VirtualMachineError vmError) {
                    throw vmError;
                }
                if (!running) {
                    break;
                }
                log.warn("Access-state broadcast listener disconnected ({}); retrying in {} ms",
                        e.getClass().getSimpleName(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        listening = false;
    }
}

package com.keevo.shared.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DeliverOnce — marks an {@code @ApplicationModuleListener} whose side effect is an outbound message that
 * cannot be taken back (push, WhatsApp) and has no natural de-duplication key (Story 12.4).
 *
 * <p>The event publication registry replays incomplete publications (restart, scheduled or manual
 * resubmission). For such a listener — an {@code @ApplicationModuleListener} or a synchronous after-commit
 * {@code @TransactionalEventListener} — the module-listener interceptor records a delivery receipt
 * {@code (listenerId, sha256(event class name + ':' + application ObjectMapper JSON))} in
 * {@code public.event_listener_receipt} in its own committed transaction <b>before</b> invoking it: a second
 * delivery of the same event is skipped (at most once). If the listener throws — a provider failure is
 * rethrown as {@link DeliveryFailedException} — the receipt is removed so a resubmission retries it.
 * Trade-off: a crash between the receipt and the send loses that one message rather than duplicating it.
 * Single-instance production (HA deferred): overlapping-instance races are out of scope (routed 16-7).
 *
 * <p>Listeners whose work is naturally idempotent (e.g. the end-of-day report keyed by store/date/type/actor)
 * must not use it: there a replay is the desired recovery.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeliverOnce {
}

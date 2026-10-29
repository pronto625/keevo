package com.keevo.shared.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.catalog.TenantScopedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ModuleListenerInterceptor — runs around every {@code @ApplicationModuleListener} method and every
 * {@link DeliverOnce} method (Story 12.4, spec-socle §7.3), inside the async worker thread and
 * <b>before</b> the listener's {@code REQUIRES_NEW} transaction starts (the tenant connection provider
 * reads {@link TenantContext} when the transaction opens its session).
 *
 * <ol>
 *   <li>Tenant binding (module listeners only — a synchronous after-commit listener binds its own):
 *       {@link TenantContext} (and the MDC {@code tenantId}) is set from the event's
 *       {@link TenantScopedEvent#tenantSchema()} — cleared for an event without tenant — and the previous
 *       values are restored in {@code finally}, whatever the outcome (a caller-runs fallback executes the
 *       listener in the publisher's thread, which keeps its own tenant).</li>
 *   <li>At-most-once delivery for {@link DeliverOnce} listeners: a receipt
 *       {@code (listenerId, sha256(event class name + ':' + application ObjectMapper JSON))} is taken first;
 *       an already-received event is skipped (the registry then marks the publication completed); a failing
 *       listener releases its receipt and rethrows (the publication stays incomplete for a later
 *       resubmission; a failing release is attached as suppressed). An event that cannot be hashed is
 *       logged at ERROR and delivered without de-duplication.</li>
 * </ol>
 */
public class ModuleListenerInterceptor implements MethodInterceptor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModuleListenerInterceptor.class);
    static final String MDC_TENANT = "tenantId";

    /**
     * After Spring Modulith's completion interceptor (HIGHEST_PRECEDENCE + 10) and before the transaction
     * interceptor (LOWEST_PRECEDENCE); the {@code @Async} interceptor always comes first.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final Supplier<EventListenerReceipts> receipts;
    private final Supplier<ObjectMapper> objectMapper;
    private final Map<Method, String> listenerIds = new ConcurrentHashMap<>();

    /** Suppliers: resolved on first use, so the advisor never initialises its collaborators early. */
    public ModuleListenerInterceptor(Supplier<EventListenerReceipts> receipts,
                                     Supplier<ObjectMapper> objectMapper) {
        this.receipts = receipts;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object[] args = invocation.getArguments();
        Object event = args.length > 0 ? args[0] : null;
        if (!AnnotatedElementUtils.hasAnnotation(method, ApplicationModuleListener.class)) {
            // a synchronous @DeliverOnce listener binds its own tenant: receipts only
            return deliverOnce(invocation, method, event);
        }
        String previousTenant = TenantContext.getCurrentTenant();
        String previousMdcTenant = MDC.get(MDC_TENANT);
        bind(tenantOf(event));
        try {
            return deliverOnce(invocation, method, event);
        } finally {
            restore(previousTenant, previousMdcTenant);
        }
    }

    private Object deliverOnce(MethodInvocation invocation, Method method, Object event) throws Throwable {
        if (event == null || !AnnotatedElementUtils.hasAnnotation(method, DeliverOnce.class)) {
            return invocation.proceed();
        }
        String listenerId = listenerIds.computeIfAbsent(method, ModuleListenerInterceptor::listenerId);
        String eventHash;
        try {
            eventHash = hash(event);
        } catch (RuntimeException | JsonProcessingException e) {
            // never block a delivery forever on an unhashable event: deliver without de-duplication
            log.error("[EVENTS] Cannot compute the delivery key of {} for {} — delivered without de-duplication",
                    event.getClass().getName(), listenerId, e);
            return invocation.proceed();
        }
        EventListenerReceipts store = receipts.get();
        if (!store.tryAcquire(listenerId, eventHash)) {
            log.info("[EVENTS] {} already delivered to {} — replay skipped",
                    event.getClass().getSimpleName(), listenerId);
            return null;
        }
        try {
            return invocation.proceed();
        } catch (Throwable failure) {
            try {
                store.release(listenerId, eventHash);
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);   // the listener's failure stays the primary one
            }
            throw failure;
        }
    }

    /** The event's tenant schema, or {@code null} for an event that is not tenant scoped. */
    static String tenantOf(Object event) {
        if (event instanceof TenantScopedEvent scoped) {
            String schema = scoped.tenantSchema();
            return schema == null || schema.isBlank() ? null : schema;
        }
        return null;
    }

    private static void bind(String tenant) {
        if (tenant != null) {
            TenantContext.setCurrentTenant(tenant);
            MDC.put(MDC_TENANT, tenant);
        } else {
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
        }
    }

    private static void restore(String previousTenant, String previousMdcTenant) {
        if (previousTenant != null) {
            TenantContext.setCurrentTenant(previousTenant);
        } else {
            TenantContext.clear();
        }
        if (previousMdcTenant != null) {
            MDC.put(MDC_TENANT, previousMdcTenant);
        } else {
            MDC.remove(MDC_TENANT);
        }
    }

    /** Same identifier as the publication registry's {@code listener_id}. */
    static String listenerId(Method method) {
        return new TransactionalApplicationListenerMethodAdapter("deliverOnce", method.getDeclaringClass(), method)
                .getListenerId();
    }

    private String hash(Object event) throws JsonProcessingException {
        String json = event.getClass().getName() + ':' + objectMapper.get().writeValueAsString(event);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

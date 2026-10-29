package com.keevo.shared.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.event.DeliverOnce;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.modulith.events.ApplicationModuleListener;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

/**
 * Test support (Story 12.4) — wraps a listener in the production {@link ModuleListenerInterceptor}, the way
 * the application context does, so unit tests calling an {@code @ApplicationModuleListener} method directly
 * run it in the event's tenant (the listeners no longer bind {@code TenantContext} themselves). Receipts are
 * an always-first-delivery stub: de-duplication is covered by the registry integration test.
 */
public final class ModuleListenerTestSupport {

    private ModuleListenerTestSupport() {}

    @SuppressWarnings("unchecked")
    public static <T> T bound(T listener) {
        EventListenerReceipts receipts = mock(EventListenerReceipts.class);
        lenient().when(receipts.tryAcquire(anyString(), anyString())).thenReturn(true);
        return bound(listener, receipts);
    }

    /** Same, with the caller's receipts (e.g. a mock to verify a release after a failed send). */
    public static <T> T bound(T listener, EventListenerReceipts receipts) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return bound(listener, new ModuleListenerInterceptor(() -> receipts, () -> mapper));
    }

    @SuppressWarnings("unchecked")
    public static <T> T bound(T listener, ModuleListenerInterceptor interceptor) {
        ProxyFactory factory = new ProxyFactory(listener);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(new ComposablePointcut(
                new AnnotationMatchingPointcut(null, ApplicationModuleListener.class, true))
                .union(new AnnotationMatchingPointcut(null, DeliverOnce.class, true)), interceptor));
        return (T) factory.getProxy();
    }
}

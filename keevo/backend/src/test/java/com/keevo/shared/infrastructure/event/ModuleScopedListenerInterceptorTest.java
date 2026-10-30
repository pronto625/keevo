package com.keevo.shared.infrastructure.event;

import com.keevo.shared.event.catalog.TenantScopedEvent;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleScoped;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 13.9 — the per-tenant {@code @ModuleScoped} listener gate. */
class ModuleScopedListenerInterceptorTest {

    record Event(String tenantSchema) implements TenantScopedEvent {
    }

    /** Class-level scope: every listener method gated; the non-listener method untouched. */
    @ModuleScoped("reporting")
    public static class ScopedListener {
        final List<String> calls = new ArrayList<>();

        @TransactionalEventListener
        public void sync(Event event) {
            calls.add("sync:" + event.tenantSchema());
        }

        @ApplicationModuleListener
        public void async(Event event) {
            calls.add("async:" + event.tenantSchema());
        }

        public void notAListener(Event event) {
            calls.add("plain:" + event.tenantSchema());
        }
    }

    /** Method-level scope only on one listener. */
    public static class MixedListener {
        final List<String> calls = new ArrayList<>();

        @EventListener
        @ModuleScoped("crm")
        public void scoped(Event event) {
            calls.add("scoped");
        }

        @EventListener
        public void unscoped(Event event) {
            calls.add("unscoped");
        }
    }

    /** Base listener scoped to one module… */
    @ModuleScoped("crm")
    public static class BaseScoped {
        final List<String> calls = new ArrayList<>();

        @EventListener
        public void on(Event event) {
            calls.add(getClass().getSimpleName());
        }
    }

    /** …a subclass re-scoped to another: the subclass annotation wins. */
    @ModuleScoped("reporting")
    public static class ReScoped extends BaseScoped {
    }

    private final ModuleRegistry registry = mock(ModuleRegistry.class);

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(new ModuleScopedListenerInterceptor.Pointcut(),
                new ModuleScopedListenerInterceptor(() -> registry)));
        return (T) factory.getProxy();
    }

    @Test
    @DisplayName("DISABLED for the event's tenant → sync and async listeners skipped (normal return); others run")
    void disabledSkips() {
        when(registry.moduleState("kv_off001", "reporting")).thenReturn(ModuleState.DISABLED);
        ScopedListener target = new ScopedListener();
        ScopedListener listener = proxy(target);
        listener.sync(new Event("kv_off001"));
        listener.async(new Event("kv_off001"));
        listener.notAListener(new Event("kv_off001"));
        assertThat(target.calls).containsExactly("plain:kv_off001");
    }

    @Test
    @DisplayName("ENABLED or READ_ONLY → delivered")
    void enabledAndReadOnlyDeliver() {
        when(registry.moduleState("kv_on0001", "reporting")).thenReturn(ModuleState.ENABLED);
        when(registry.moduleState("kv_ro0001", "reporting")).thenReturn(ModuleState.READ_ONLY);
        ScopedListener target = new ScopedListener();
        ScopedListener listener = proxy(target);
        listener.sync(new Event("kv_on0001"));
        listener.async(new Event("kv_ro0001"));
        assertThat(target.calls).containsExactly("sync:kv_on0001", "async:kv_ro0001");
    }

    @Test
    @DisplayName("no tenant / invalid tenant → delivered without asking the registry")
    void noTenantDelivers() {
        ScopedListener target = new ScopedListener();
        ScopedListener listener = proxy(target);
        listener.sync(new Event(null));
        listener.sync(new Event("not a schema"));
        assertThat(target.calls).hasSize(2);
        verify(registry, never()).moduleState(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("registry failure → WARN and delivered (no behaviour change)")
    void registryFailureDelivers() {
        when(registry.moduleState("kv_err001", "reporting")).thenThrow(new IllegalStateException("db down"));
        ScopedListener target = new ScopedListener();
        proxy(target).sync(new Event("kv_err001"));
        assertThat(target.calls).containsExactly("sync:kv_err001");
    }

    @Test
    @DisplayName("method-level scope: only the annotated listener is gated, by its own module")
    void methodLevel() {
        when(registry.moduleState("kv_off001", "crm")).thenReturn(ModuleState.DISABLED);
        MixedListener target = new MixedListener();
        MixedListener listener = proxy(target);
        listener.scoped(new Event("kv_off001"));
        listener.unscoped(new Event("kv_off001"));
        assertThat(target.calls).containsExactly("unscoped");
    }

    @Test
    @DisplayName("order: after Modulith's completion interceptor, before the tenant binding interceptor")
    void order() {
        assertThat(ModuleScopedListenerInterceptor.ORDER).isGreaterThan(Integer.MIN_VALUE + 10)
                .isLessThan(ModuleListenerInterceptor.ORDER);
        assertThat(ModuleScopedListenerInterceptor.tenantCode("kv_ab12cd")).isEqualTo("KV-AB12CD");
    }

    @Test
    @DisplayName("subclass annotation wins over the base class; the gate is cached per (method, target class)")
    void subclassWinsAndPerClassCache() {
        when(registry.moduleState("kv_mix001", "crm")).thenReturn(ModuleState.DISABLED);
        when(registry.moduleState("kv_mix001", "reporting")).thenReturn(ModuleState.ENABLED);
        ModuleScopedListenerInterceptor interceptor = new ModuleScopedListenerInterceptor(() -> registry);
        BaseScoped base = new BaseScoped();
        ReScoped sub = new ReScoped();
        for (Object target : List.of(base, sub, base, sub)) {
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvisor(new DefaultPointcutAdvisor(new ModuleScopedListenerInterceptor.Pointcut(), interceptor));
            ((BaseScoped) factory.getProxy()).on(new Event("kv_mix001"));
        }
        assertThat(base.calls).isEmpty(); // crm DISABLED
        assertThat(sub.calls).containsExactly("ReScoped", "ReScoped"); // reporting ENABLED, same Method object
    }
}

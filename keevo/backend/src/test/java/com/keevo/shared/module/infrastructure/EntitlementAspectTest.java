package com.keevo.shared.module.infrastructure;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.RequiresEntitlement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Story 13.5 — {@link EntitlementAspect} on a proxied plain bean: tenant source, READ/WRITE, type vs method. */
class EntitlementAspectTest {

    private final EntitlementService entitlements = mock(EntitlementService.class);

    @RequiresEntitlement("crm")
    static class CrmUseCase {
        public String write() {
            return "written";
        }

        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String read() {
            return "read";
        }

        @RequiresEntitlement(value = "invoicing", access = RequiresEntitlement.Access.READ)
        public String other() {
            return "other";
        }
    }

    @RequiresEntitlement("crm")
    static class GatedController {
        public String handle() {
            return "handled";
        }

        @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
        public String onError() {
            return "error-page";
        }

        @Override
        public String toString() {
            return "gated-controller";
        }
    }

    static class Ungated {
        public String free() {
            return "free";
        }
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new EntitlementAspect(entitlements));
        return factory.getProxy();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("type-level annotation → WRITE check (default) with the tenant from TenantContext")
    void typeLevelWrite() {
        TenantContext.setCurrentTenant("kv_abc123");
        assertThat(proxy(new CrmUseCase()).write()).isEqualTo("written");
        verify(entitlements).assertWritable("kv_abc123", "crm");
    }

    @Test
    @DisplayName("method-level annotation overrides the type-level one (READ → assertEnabled, other module)")
    void methodLevelOverrides() {
        TenantContext.setCurrentTenant("kv_abc123");
        CrmUseCase useCase = proxy(new CrmUseCase());
        assertThat(useCase.read()).isEqualTo("read");
        assertThat(useCase.other()).isEqualTo("other");
        verify(entitlements).assertEnabled("kv_abc123", "crm");
        verify(entitlements).assertEnabled("kv_abc123", "invoicing");
    }

    @Test
    @DisplayName("a refusal propagates and the method body never runs")
    void refusalPropagates() {
        TenantContext.setCurrentTenant("kv_abc123");
        doThrow(new DomainException("MODULE_READ_ONLY")).when(entitlements).assertWritable("kv_abc123", "crm");
        assertThatThrownBy(() -> proxy(new CrmUseCase()).write()).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("no tenant bound → IllegalStateException, entitlement never consulted (no open door)")
    void noTenant() {
        CrmUseCase useCase = proxy(new CrmUseCase());
        assertThatThrownBy(useCase::write).isInstanceOf(IllegalStateException.class);
        TenantContext.setCurrentTenant("  ");
        assertThatThrownBy(useCase::read).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(entitlements);
    }

    @Test
    @DisplayName("an ungated bean is not touched")
    void ungated() {
        assertThat(proxy(new Ungated()).free()).isEqualTo("free");
        verifyNoInteractions(entitlements);
    }

    @Test
    @DisplayName("class-level annotation: Object methods (even overridden) and @ExceptionHandler are not gated")
    void excludedJoinPoints() {
        GatedController controller = proxy(new GatedController()); // no tenant bound: a gated call would throw
        assertThat(controller.toString()).isEqualTo("gated-controller");
        assertThat(controller.onError()).isEqualTo("error-page");
        controller.hashCode();
        assertThat(controller.equals(controller)).isTrue();
        verifyNoInteractions(entitlements);
        assertThatThrownBy(controller::handle).isInstanceOf(IllegalStateException.class);
    }
}

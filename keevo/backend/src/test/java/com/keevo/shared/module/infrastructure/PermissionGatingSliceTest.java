package com.keevo.shared.module.infrastructure;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleEntitlementService;
import com.keevo.shared.module.application.ModulePermissions;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 14.1 — {@link RequiresPermission} combined with {@link RequiresEntitlement} through a test-only controller
 * behind the real security chain, method security, the entitlement aspect and {@code GlobalExceptionHandler}:
 * evaluation order role ({@code @PreAuthorize}) → permission → entitlement; a missing permission is 403
 * {@code FORBIDDEN} even when the module is disabled.
 */
@WebMvcTest(PermissionGatingSliceTest.PermissionTestController.class)
@Import({SecurityConfig.class, EntitlementAspect.class, PermissionMethodSecurityConfig.class,
        PermissionGatingSliceTest.Wiring.class, PermissionGatingSliceTest.PermissionTestController.class})
@DisplayName("@RequiresPermission + @RequiresEntitlement — HTTP slice (Story 14.1)")
class PermissionGatingSliceTest {

    static final String TENANT = "kv_perm01";
    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    static final Map<String, Row> ROWS = new ConcurrentHashMap<>();
    static final AtomicInteger HANDLER_CALLS = new AtomicInteger();
    static final AtomicReference<String> ROLE = new AtomicReference<>("OWNER");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class Wiring {
        @Bean
        ModuleCatalog moduleCatalog() {
            return new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                    TestModuleCatalogs.withPermissions("invoicing", ModuleCategory.PREMIUM, false,
                            List.of("INVOICE_VIEW", "INVOICE_MANAGE"))));
        }

        @Bean
        ModuleRegistry moduleRegistry(ModuleCatalog catalog) {
            TenantModuleStatePort port = schema -> TENANT.equals(schema) ? Optional.of(Map.copyOf(ROWS))
                    : Optional.empty();
            return new ModuleRegistryService(catalog, port, new RequestScopedActiveModulesCache(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        EntitlementService entitlementService(ModuleRegistry registry) {
            return new ModuleEntitlementService(registry);
        }

        @Bean
        ModulePermissions modulePermissions(ModuleCatalog catalog) {
            KeevoModule invoicing = new KeevoModule() {
                @Override
                public String id() {
                    return "invoicing";
                }

                @Override
                public Set<String> employeePermissions() {
                    return Set.of("INVOICE_VIEW");
                }
            };
            return new ModulePermissions(catalog, KeevoModuleDirectory.of(catalog, List.of(invoicing)));
        }
    }

    @RestController
    @RequestMapping("/api/v1/test-permission")
    static class PermissionTestController {

        @GetMapping("/invoices")
        @RequiresPermission("INVOICE_VIEW")
        @RequiresEntitlement(value = "invoicing", access = RequiresEntitlement.Access.READ)
        public Map<String, String> list() {
            HANDLER_CALLS.incrementAndGet();
            return Map.of("ok", "listed");
        }

        @PostMapping("/invoices/cancel")
        @RequiresPermission("INVOICE_MANAGE")
        @RequiresEntitlement("invoicing")
        public Map<String, String> cancel() {
            HANDLER_CALLS.incrementAndGet();
            return Map.of("ok", "cancelled");
        }

        @PostMapping("/invoices/owner-only")
        @PreAuthorize("hasRole('OWNER')")
        @RequiresPermission("INVOICE_VIEW")
        @RequiresEntitlement("invoicing")
        public Map<String, String> ownerOnly() {
            HANDLER_CALLS.incrementAndGet();
            return Map.of("ok", "owner");
        }
    }

    @BeforeEach
    void authenticateAndBindTenant() throws Exception {
        ROWS.clear();
        HANDLER_CALLS.set(0);
        ROLE.set("OWNER");
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + ROLE.get())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            TenantContext.setCurrentTenant(TENANT);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("interceptor order: @PreAuthorize < @RequiresPermission < EntitlementAspect")
    void order() {
        assertThat(PermissionMethodSecurityConfig.ORDER)
                .isGreaterThan(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder())
                .isLessThan(EntitlementAspect.ORDER);
    }

    @Test
    @DisplayName("OWNER with the module enabled → 200 on both")
    void ownerEntitled() throws Exception {
        ROWS.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        mockMvc.perform(get("/api/v1/test-permission/invoices")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/test-permission/invoices/cancel")).andExpect(status().isOk());
        assertThat(HANDLER_CALLS).hasValue(2);
    }

    @Test
    @DisplayName("EMPLOYEE: granted permission → 200; owner permission → 403 FORBIDDEN, handler never runs")
    void employee() throws Exception {
        ROWS.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        ROLE.set("EMPLOYEE");
        mockMvc.perform(get("/api/v1/test-permission/invoices")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/test-permission/invoices/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error").value("Vous n'avez pas les droits nécessaires"));
        assertThat(HANDLER_CALLS).hasValue(1);
    }

    @Test
    @DisplayName("SUPER_ADMIN holds no module permission → 403 FORBIDDEN")
    void superAdmin() throws Exception {
        ROWS.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        ROLE.set("SUPER_ADMIN");
        mockMvc.perform(get("/api/v1/test-permission/invoices"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        assertThat(HANDLER_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("permission before entitlement: EMPLOYEE + module disabled → FORBIDDEN; OWNER → MODULE_NOT_ENTITLED")
    void permissionFirst() throws Exception {
        ROLE.set("EMPLOYEE");
        mockMvc.perform(post("/api/v1/test-permission/invoices/cancel"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        ROLE.set("OWNER");
        mockMvc.perform(post("/api/v1/test-permission/invoices/cancel"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("MODULE_NOT_ENTITLED"));
        assertThat(HANDLER_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("role before permission: @PreAuthorize refuses an EMPLOYEE although it holds the permission")
    void roleFirst() throws Exception {
        ROWS.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        ROLE.set("EMPLOYEE");
        mockMvc.perform(post("/api/v1/test-permission/invoices/owner-only"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        ROLE.set("OWNER");
        mockMvc.perform(post("/api/v1/test-permission/invoices/owner-only")).andExpect(status().isOk());
        assertThat(HANDLER_CALLS).hasValue(1);
    }
}

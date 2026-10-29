package com.keevo.shared.module.infrastructure;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.ModuleEntitlementService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.5 — {@link RequiresEntitlement} end to end through a test-only controller behind the real security
 * chain and {@code GlobalExceptionHandler}: the real {@link ModuleEntitlementService} and
 * {@link ModuleRegistryService} on a fake {@code tenant_modules} port (paid module {@code invoicing}).
 */
@WebMvcTest(EntitlementGatingSliceTest.InvoicingTestController.class)
@Import({SecurityConfig.class, EntitlementAspect.class, EntitlementGatingSliceTest.Wiring.class,
        EntitlementGatingSliceTest.InvoicingTestController.class})
@DisplayName("@RequiresEntitlement — HTTP slice")
class EntitlementGatingSliceTest {

    static final String TENANT = "kv_ent001";
    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    /** Rows of the tenant, per module id (the fake port reads it). */
    static final Map<String, Row> ROWS = new ConcurrentHashMap<>();
    static final AtomicInteger HANDLER_CALLS = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class Wiring {
        @Bean
        ModuleRegistry moduleRegistry() {
            TenantModuleStatePort port = schema -> TENANT.equals(schema) ? Optional.of(Map.copyOf(ROWS))
                    : Optional.empty();
            return new ModuleRegistryService(testCatalog(), port, new RequestScopedActiveModulesCache(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        EntitlementService entitlementService(ModuleRegistry registry) {
            return new ModuleEntitlementService(registry);
        }
    }

    @RestController
    @RequestMapping("/api/v1/test-entitlement")
    static class InvoicingTestController {

        @PostMapping("/invoices")
        @RequiresEntitlement("invoicing")
        public Map<String, String> create() {
            HANDLER_CALLS.incrementAndGet();
            return Map.of("ok", "created");
        }

        @GetMapping("/invoices")
        @RequiresEntitlement(value = "invoicing", access = RequiresEntitlement.Access.READ)
        public Map<String, String> list() {
            HANDLER_CALLS.incrementAndGet();
            return Map.of("ok", "listed");
        }
    }

    @BeforeEach
    void authenticateAndBindTenant() throws Exception {
        ROWS.clear();
        HANDLER_CALLS.set(0);
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
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
    @DisplayName("paid module, no row → 403 MODULE_NOT_ENTITLED with the French message, handler never runs")
    void notEntitled() throws Exception {
        mockMvc.perform(post("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("MODULE_NOT_ENTITLED"))
                .andExpect(jsonPath("$.error").value("Ce module n'est pas inclus dans votre abonnement"));
        mockMvc.perform(get("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("MODULE_NOT_ENTITLED"));
        assertThat(HANDLER_CALLS).hasValue(0);
    }

    @Test
    @DisplayName("TRIAL or ACTIVE → 200 on WRITE and READ")
    void entitled() throws Exception {
        ROWS.put("invoicing", new Row(TenantModuleStatus.TRIAL, NOW.plus(Duration.ofDays(14)), null, "PREMIUM"));
        mockMvc.perform(post("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value("created"));
        ROWS.put("invoicing", Row.of(TenantModuleStatus.ACTIVE));
        mockMvc.perform(get("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value("listed"));
        assertThat(HANDLER_CALLS).hasValue(2);
    }

    @Test
    @DisplayName("expired in grace (read-only): WRITE → 403 MODULE_READ_ONLY, READ → 200")
    void readOnly() throws Exception {
        ROWS.put("invoicing", new Row(TenantModuleStatus.ACTIVE, NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(7)), "PREMIUM"));
        mockMvc.perform(post("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("MODULE_READ_ONLY"))
                .andExpect(jsonPath("$.error").value("Ce module est en lecture seule : abonnement expiré"));
        mockMvc.perform(get("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value("listed"));
        assertThat(HANDLER_CALLS).hasValue(1);
    }

    @Test
    @DisplayName("grace over → 403 MODULE_NOT_ENTITLED on READ as well")
    void graceOver() throws Exception {
        ROWS.put("invoicing", new Row(TenantModuleStatus.EXPIRED, NOW.minus(Duration.ofDays(8)),
                NOW.minus(Duration.ofDays(1)), "PREMIUM"));
        mockMvc.perform(get("/api/v1/test-entitlement/invoices"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("MODULE_NOT_ENTITLED"));
    }
}

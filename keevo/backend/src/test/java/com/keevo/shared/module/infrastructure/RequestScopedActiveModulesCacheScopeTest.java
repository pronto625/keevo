package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleStateScope;
import com.keevo.shared.module.application.port.ActiveModulesCache.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 17.8 follow-up — the module-state cache inside a {@link ModuleStateScope} (scheduler / listener work). */
class RequestScopedActiveModulesCacheScopeTest {

    private static final String TENANT = "kv_scope01";
    private final RequestScopedActiveModulesCache cache = new RequestScopedActiveModulesCache();
    private final Entry entry = new Entry(true, Map.of());

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("no request, no scope → nothing cached (unchanged)")
    void noScopeNoCache() {
        cache.put(TENANT, entry);
        assertThat(cache.get(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("scope: cached until evict or close; a nested scope joins the outer one")
    void scopeCachesUntilEvictOrClose() {
        try (var outer = ModuleStateScope.open()) {
            cache.put(TENANT, entry);
            try (var inner = ModuleStateScope.open()) {
                assertThat(cache.get(TENANT)).contains(entry);
            }
            assertThat(cache.get(TENANT)).contains(entry); // the inner close keeps the outer unit of work
            cache.evict(TENANT);
            assertThat(cache.get(TENANT)).isEmpty();
            cache.put(TENANT, entry);
        }
        assertThat(ModuleStateScope.entries()).isEmpty();
        assertThat(cache.get(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("HTTP request bound → the request cache wins, the scope is not used")
    void requestWins() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try (var scope = ModuleStateScope.open()) {
            cache.put(TENANT, entry);
            assertThat(ModuleStateScope.entries().orElseThrow()).isEmpty();
            assertThat(cache.get(TENANT)).contains(entry);
        }
    }
}

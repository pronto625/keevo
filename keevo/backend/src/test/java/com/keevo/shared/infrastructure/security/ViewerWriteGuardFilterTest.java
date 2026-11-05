package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ViewerWriteGuardFilter — a VIEWER never writes (Story 19.5)")
class ViewerWriteGuardFilterTest {

    private final ViewerWriteGuardFilter filter = new ViewerWriteGuardFilter(new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** @return the response; {@code chain.getRequest() != null} when the request went through */
    private MockFilterChain run(String method, String path, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        return chain;
    }

    @Test
    @DisplayName("VIEWER POST/PUT/PATCH/DELETE on a business path → 403 FORBIDDEN, chain not called")
    void viewerWritesBlocked() throws Exception {
        actAs("VIEWER");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = run(method, "/api/v1/products/" + UUID.randomUUID(), response);
            assertThat(chain.getRequest()).as(method).isNull();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("\"domainCode\":\"FORBIDDEN\"", "Vous n'avez pas les droits nécessaires");
        }
    }

    @Test
    @DisplayName("VIEWER reads and self-service writes pass")
    void viewerReadsAndSelfServicePass() throws Exception {
        actAs("VIEWER");
        for (String[] call : List.of(new String[]{"GET", "/api/v1/products"}, new String[]{"HEAD", "/api/v1/products"},
                new String[]{"OPTIONS", "/api/v1/products"}, new String[]{"POST", "/api/v1/auth/change-password"},
                new String[]{"PATCH", "/api/v1/notifications/" + UUID.randomUUID() + "/read"},
                new String[]{"POST", "/api/v1/devices/token"}, new String[]{"POST", "/api/v1/web-push/subscribe"},
                new String[]{"POST", "/api/v1/feedback"}, new String[]{"POST", "/api/v1/sync/push"})) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(run(call[0], call[1], response).getRequest()).as(call[0] + " " + call[1]).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the allow-list is exact: other writes under the same prefixes, or another verb, stay blocked")
    void allowListIsExact() throws Exception {
        actAs("VIEWER");
        for (String[] call : List.of(new String[]{"POST", "/api/v1/auth/some-new-write"},
                new String[]{"PUT", "/api/v1/devices/token"}, new String[]{"POST", "/api/v1/notifications/purge"},
                new String[]{"DELETE", "/api/v1/feedback/" + UUID.randomUUID()},
                new String[]{"POST", "/api/v1/sync/push/extra"})) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(run(call[0], call[1], response).getRequest()).as(call[0] + " " + call[1]).isNull();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("matching uses the path within the application (context path stripped)")
    void pathWithinApplication() throws Exception {
        actAs("VIEWER");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ctx/api/v1/sync/push");
        request.setContextPath("/ctx");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("OWNER, MANAGER, EMPLOYEE and anonymous writes are not touched")
    void otherRolesPass() throws Exception {
        for (String role : List.of("OWNER", "MANAGER", "EMPLOYEE")) {
            actAs(role);
            assertThat(run("POST", "/api/v1/products", new MockHttpServletResponse()).getRequest()).isNotNull();
        }
        SecurityContextHolder.clearContext();
        assertThat(run("POST", "/api/v1/products", new MockHttpServletResponse()).getRequest()).isNotNull();
    }
}

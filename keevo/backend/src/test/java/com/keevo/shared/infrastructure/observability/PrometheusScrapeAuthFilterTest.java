package com.keevo.shared.infrastructure.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PrometheusScrapeAuthFilterTest — Story 11.2: {@code /actuator/prometheus} only with the static
 * bearer token; 404 when no token is configured, 401 on a missing/wrong one.
 */
@DisplayName("PrometheusScrapeAuthFilter (scrape token guard)")
class PrometheusScrapeAuthFilterTest {

    private static final String TOKEN = "s3cr3t-scrape-token";

    private final FilterChain chain = mock(FilterChain.class);

    private MockHttpServletResponse run(PrometheusScrapeAuthFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest scrape(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    @DisplayName("correct bearer token → request continues to the endpoint")
    void correctTokenPasses() throws Exception {
        MockHttpServletResponse response = run(new PrometheusScrapeAuthFilter(TOKEN), scrape("Bearer " + TOKEN));

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest(name = "[{index}] Authorization = {0}")
    @ValueSource(strings = {"Bearer wrong", "Bearer ", "Bearer s3cr3t-scrape-tokenX", "Basic czNjcjN0",
            "s3cr3t-scrape-token", "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.sig"})
    @DisplayName("wrong token → 401, empty body, chain not called")
    void wrongTokenRejected(String authorization) throws Exception {
        MockHttpServletResponse response = run(new PrometheusScrapeAuthFilter(TOKEN), scrape(authorization));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("missing Authorization header → 401")
    void missingTokenRejected() throws Exception {
        MockHttpServletResponse response = run(new PrometheusScrapeAuthFilter(TOKEN), scrape(null));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest(name = "[{index}] token = \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("token unset or blank → 404, even with a bearer header")
    void disabledWhenTokenBlank(String configured) throws Exception {
        MockHttpServletResponse response = run(new PrometheusScrapeAuthFilter(configured), scrape("Bearer   "));

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("trailing slash does not bypass the guard")
    void trailingSlashGuarded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus/");

        assertThat(run(new PrometheusScrapeAuthFilter(TOKEN), request).getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/actuator/health", "/actuator/metrics", "/api/v1/sales", "/actuator/prometheusX"})
    @DisplayName("other paths are untouched, whatever the token configuration")
    void otherPathsUntouched(String path) throws Exception {
        MockHttpServletResponse response = run(new PrometheusScrapeAuthFilter(null),
                new MockHttpServletRequest("GET", path));

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }
}

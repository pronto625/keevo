package com.keevo.shared.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CorrelationIdFilterTest — Story 11.1: traceId in MDC, echoed in X-Request-Id, always cleaned up.
 * Story 11.2: the OTel trace id of the current span wins over the generated/client id; the client id
 * goes to MDC {@code requestId} and stays the echoed header.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorrelationIdFilter (traceId MDC)")
class CorrelationIdFilterTest {

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Mock FilterChain filterChain;

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter(noTracer());
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private static final String OTEL_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static ObjectProvider<Tracer> noTracer() {
        return new StaticListableBeanFactory().getBeanProvider(Tracer.class);
    }

    private static ObjectProvider<Tracer> tracerWithCurrentSpan(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        lenient().when(context.traceId()).thenReturn(traceId);
        lenient().when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        return factory.getBeanProvider(Tracer.class);
    }

    private String requestIdSeenByChain(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get("requestId"));
            return null;
        }).when(filterChain).doFilter(any(), any());
        filter.doFilterInternal(request, response, filterChain);
        return seen.get();
    }

    private String traceIdSeenByChain(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get("traceId"));
            return null;
        }).when(filterChain).doFilter(any(), any());
        filter.doFilterInternal(request, response, filterChain);
        return seen.get();
    }

    @Test
    @DisplayName("generates a UUID traceId when X-Request-Id is absent, echoes it, cleans up")
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = traceIdSeenByChain(new MockHttpServletRequest(), response);

        assertThat(seen).matches(UUID_REGEX);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen);
        assertThat(MDC.get("traceId")).isNull();
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("blank X-Request-Id is treated as absent")
    void generatesTraceIdWhenHeaderBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(request, response)).matches(UUID_REGEX);
    }

    @Test
    @DisplayName("reuses the client-supplied X-Request-Id and echoes it back")
    void reusesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(request, response)).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("keeps printable ASCII only: CR/LF, tab, NUL, DEL and spaces are stripped")
    void stripsControlCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc\r\nInjected: evil\t\u0000\u007F-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = traceIdSeenByChain(request, response);

        assertThat(seen).isEqualTo("abcInjected:evil-1");
        assertThat(seen).matches("[\\x21-\\x7E]+");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen);
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("C1 controls, U+2028/U+2029 and non-ASCII characters are stripped too")
    void stripsC1LineSeparatorsAndNonAscii() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "id\u0085-\u009B1\u2028\u2029-é\u00A0\u4E2D-ok");

        assertThat(traceIdSeenByChain(request, new MockHttpServletResponse())).isEqualTo("id-1--ok");
    }

    @Test
    @DisplayName("an X-Request-Id made only of control characters falls back to a generated UUID")
    void controlOnlyHeaderFallsBackToUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "\r\n");

        assertThat(traceIdSeenByChain(request, new MockHttpServletResponse())).matches(UUID_REGEX);
    }

    @Test
    @DisplayName("truncates X-Request-Id longer than 256 characters, never rejects")
    void truncatesOverlongHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "a".repeat(1000));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = traceIdSeenByChain(request, response);

        assertThat(seen).hasSize(256).isEqualTo("a".repeat(256));
        assertThat(response.getHeader("X-Request-Id")).hasSize(256);
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("clears MDC even when the filter chain throws")
    void clearsMdcOnException() throws Exception {
        doThrow(new ServletException("Simulated error")).when(filterChain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilterInternal(
                new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("overwrites an outer traceId during the chain, then restores it (outer tracing scope)")
    void overwritesThenRestoresOuterTraceId() throws Exception {
        MDC.put("traceId", "outer-scope-trace-id");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "fresh-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(request, response)).isEqualTo("fresh-1");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("fresh-1");
        assertThat(MDC.get("traceId")).isEqualTo("outer-scope-trace-id");
    }

    @Test
    @DisplayName("restores the outer traceId even when the chain throws")
    void restoresOuterTraceIdOnException() throws Exception {
        MDC.put("traceId", "outer-scope-trace-id");
        doThrow(new ServletException("Simulated error")).when(filterChain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilterInternal(
                new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("traceId")).isEqualTo("outer-scope-trace-id");
    }

    @Test
    @DisplayName("two sequential requests on the same thread get distinct traceIds")
    void sequentialRequestsDoNotShareTraceId() throws Exception {
        String first = traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse());
        String second = traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(first).isNotEqualTo(second);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("public / unauthenticated paths still get a traceId")
    void publicPathsGetTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(request, response)).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(MDC.get("traceId")).isNull();
    }

    // ── Story 11.2 — OpenTelemetry trace id preference ────────────────────────────

    @Test
    @DisplayName("11.2 — current span: traceId = OTel trace id, echoed when no client id")
    void currentSpanTraceIdWins() throws Exception {
        filter = new CorrelationIdFilter(tracerWithCurrentSpan(OTEL_TRACE_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(new MockHttpServletRequest(), response)).isEqualTo(OTEL_TRACE_ID);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(OTEL_TRACE_ID);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("11.2 — current span + client id: traceId = span id, requestId = client id, header echoes client id")
    void clientIdGoesToRequestIdWhenSpanCurrent() throws Exception {
        filter = new CorrelationIdFilter(tracerWithCurrentSpan(OTEL_TRACE_ID));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(traceIdSeenByChain(request, response)).isEqualTo(OTEL_TRACE_ID);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");

        MockHttpServletRequest again = new MockHttpServletRequest();
        again.addHeader("X-Request-Id", "abc-123");
        assertThat(requestIdSeenByChain(again, new MockHttpServletResponse())).isEqualTo("abc-123");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("11.2 — no span: client id is both traceId (11.1 fallback) and requestId")
    void noSpanFallsBackToClientId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");

        assertThat(requestIdSeenByChain(request, new MockHttpServletResponse())).isEqualTo("abc-123");
        assertThat(MDC.get("requestId")).isNull();

        MockHttpServletRequest again = new MockHttpServletRequest();
        again.addHeader("X-Request-Id", "abc-123");
        assertThat(traceIdSeenByChain(again, new MockHttpServletResponse())).isEqualTo("abc-123");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("11.2 — no client id: no requestId in the MDC during the chain; outer value restored after")
    void noClientIdNoRequestId() throws Exception {
        assertThat(requestIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse())).isNull();
        assertThat(MDC.get("requestId")).isNull();

        MDC.put("requestId", "outer");
        assertThat(requestIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse())).isNull();
        assertThat(MDC.get("requestId")).isEqualTo("outer");
    }

    @Test
    @DisplayName("11.2 — tracer present but no current span: 11.1 generated UUID")
    void tracerWithoutSpanFallsBack() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        filter = new CorrelationIdFilter(factory.getBeanProvider(Tracer.class));

        assertThat(traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .matches(UUID_REGEX);
    }

    @Test
    @DisplayName("11.2 — unsampled span (isNoop() = true in the OTel bridge) still provides the trace id")
    void unsampledSpanTraceIdUsed() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(OTEL_TRACE_ID);
        when(span.context()).thenReturn(context);
        lenient().when(span.isNoop()).thenReturn(true);
        when(tracer.currentSpan()).thenReturn(span);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        filter = new CorrelationIdFilter(factory.getBeanProvider(Tracer.class));

        assertThat(traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isEqualTo(OTEL_TRACE_ID);
    }

    @Test
    @DisplayName("11.2 — all-zero (invalid) span trace id falls back to the 11.1 id")
    void invalidSpanTraceIdFallsBack() throws Exception {
        filter = new CorrelationIdFilter(tracerWithCurrentSpan("00000000000000000000000000000000"));

        assertThat(traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .matches(UUID_REGEX);
    }

    @Test
    @DisplayName("11.2 — blank span trace id (no-op context) falls back to the 11.1 id")
    void blankSpanTraceIdFallsBack() throws Exception {
        filter = new CorrelationIdFilter(tracerWithCurrentSpan(""));

        assertThat(traceIdSeenByChain(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .matches(UUID_REGEX);
    }

    @Test
    @DisplayName("11.2 — runs right after Boot's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1)")
    void orderedAfterObservationFilter() {
        org.springframework.core.annotation.Order order =
                CorrelationIdFilter.class.getAnnotation(org.springframework.core.annotation.Order.class);
        assertThat(order.value()).isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
        // still well ahead of the Spring Security chain (-100)
        assertThat(order.value()).isLessThan(-100);
    }
}

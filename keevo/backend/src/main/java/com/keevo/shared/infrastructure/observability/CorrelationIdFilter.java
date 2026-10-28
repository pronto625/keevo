package com.keevo.shared.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CorrelationIdFilter — one correlation key per request in the MDC (Stories 11.1 + 11.2, MOD-NFR4).
 *
 * <ul>
 *   <li>MDC {@code traceId} = the OpenTelemetry trace id of the current span (Story 11.2), so logs and
 *       exported traces share the same id. Without a current span (tracing off), the 11.1 fallback:
 *       the sanitized inbound {@code X-Request-Id}, or a generated UUID.</li>
 *   <li>MDC {@code requestId} = the sanitized client-supplied {@code X-Request-Id}, when present.</li>
 *   <li>Response {@code X-Request-Id} = the client id when supplied (echo), else the effective
 *       {@code traceId}.</li>
 * </ul>
 * Both MDC keys are restored in {@code finally} to their value before the filter (removed when there
 * was none), so a pooled thread never carries them into the next request and the tracing bridge's
 * outer scope keeps its {@code traceId}.
 *
 * <p>Ordered just after Boot's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}),
 * so the request span is already current; still ahead of the security chain, so the 401/403 written
 * later by {@code JwtAuthFilter} carry the id too.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** After {@code ServerHttpObservationFilter} (HIGHEST_PRECEDENCE + 1), before everything else. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    static final String HEADER_NAME = "X-Request-Id";
    static final String MDC_KEY = "traceId";
    static final String MDC_REQUEST_ID = "requestId";
    static final int MAX_TRACE_ID_LENGTH = 256;

    /** All-zero trace id = OTel "invalid" span context. */
    private static final Pattern INVALID_TRACE_ID = Pattern.compile("0+");
    /** Anything but printable ASCII (C0/C1 controls, DEL, space, U+2028/U+2029, non-ASCII). */
    private static final Pattern NOT_PRINTABLE_ASCII = Pattern.compile("[^\\x21-\\x7E]");

    private final ObjectProvider<Tracer> tracer;

    public CorrelationIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientId = sanitizedClientId(request.getHeader(HEADER_NAME));
        String spanTraceId = currentSpanTraceId();
        String traceId = spanTraceId != null
                ? spanTraceId
                : clientId != null ? clientId : UUID.randomUUID().toString();

        // The outer ServerHttpObservationFilter scope (tracing bridge) owns the MDC traceId while it
        // is open: restore what was there instead of removing it under the bridge's feet.
        String previousTraceId = MDC.get(MDC_KEY);
        String previousRequestId = MDC.get(MDC_REQUEST_ID);
        MDC.put(MDC_KEY, traceId);
        if (clientId != null) {
            MDC.put(MDC_REQUEST_ID, clientId);
        } else {
            MDC.remove(MDC_REQUEST_ID);
        }
        response.setHeader(HEADER_NAME, clientId != null ? clientId : traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            restore(MDC_KEY, previousTraceId);
            restore(MDC_REQUEST_ID, previousRequestId);
        }
    }

    private static void restore(String key, String previous) {
        if (previous != null) {
            MDC.put(key, previous);
        } else {
            MDC.remove(key);
        }
    }

    /**
     * Trace id of the current span, or null when tracing is off / no span / invalid id.
     *
     * <p>{@code Span.isNoop()} is deliberately NOT checked: the OTel bridge reports every
     * <em>unsampled</em> span (90 % at the default 0.1 probability) as no-op, yet its trace id is
     * valid and is the one the bridge puts in the MDC. Only a blank or all-zero id is rejected.
     */
    private String currentSpanTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        String id = span.context().traceId();
        return id == null || id.isBlank() || INVALID_TRACE_ID.matcher(id).matches() ? null : id;
    }


    /** Sanitized inbound id, or null when absent or blank once sanitized. */
    static String sanitizedClientId(String incomingId) {
        if (incomingId == null) {
            return null;
        }
        String sanitized = sanitize(incomingId);
        return sanitized.isBlank() ? null : sanitized;
    }

    /** Keeps printable ASCII only ({@code [\x21-\x7E]}) and caps the length — header and log injection. */
    private static String sanitize(String value) {
        String sanitized = NOT_PRINTABLE_ASCII.matcher(value).replaceAll("");
        return sanitized.length() > MAX_TRACE_ID_LENGTH
                ? sanitized.substring(0, MAX_TRACE_ID_LENGTH)
                : sanitized;
    }
}

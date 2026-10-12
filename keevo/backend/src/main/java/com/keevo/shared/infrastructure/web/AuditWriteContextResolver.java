package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * AuditWriteContextResolver — resolves the channel facts of an audit write at write time
 * (Story v1s-18-6): source, device, actor label and fallback store.
 *
 * <ul>
 *   <li>{@code SYNC} when inside {@link AuditContext#runAsSync} (sync push), device = push deviceId;</li>
 *   <li>{@code ONLINE} when an HTTP request is bound to the thread, device = sanitized
 *       {@code X-Device-Id} header (control chars removed, max 100 chars);</li>
 *   <li>{@code SYSTEM} otherwise (schedulers, automatic day close).</li>
 * </ul>
 * Never throws: an audit write must not fail because its context could not be read.
 */
@Component
public class AuditWriteContextResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditWriteContextResolver.class);

    public static final String SOURCE_ONLINE = "ONLINE";
    public static final String SOURCE_SYNC = "SYNC";
    public static final String SOURCE_SYSTEM = "SYSTEM";
    public static final String DEVICE_HEADER = "X-Device-Id";
    static final int DEVICE_ID_MAX = 100;

    /** Resolved write context; every field but {@code source} is nullable. */
    public record WriteContext(String source, String deviceId, String actorLabel, UUID fallbackStoreId) {}

    public WriteContext resolve() {
        try {
            String actorLabel = isAuthenticated() ? CurrentActor.actorLabel() : null;
            UUID store = CurrentActor.assignedStoreIdOrNull();

            var sync = AuditContext.current();
            if (sync.isPresent()) {
                return new WriteContext(SOURCE_SYNC, sanitizeDeviceId(sync.get().deviceId()), actorLabel, store);
            }
            HttpServletRequest request = currentRequest();
            if (request != null) {
                return new WriteContext(SOURCE_ONLINE, sanitizeDeviceId(request.getHeader(DEVICE_HEADER)),
                        actorLabel, store);
            }
            return new WriteContext(SOURCE_SYSTEM, null, actorLabel, store);
        } catch (RuntimeException e) {
            log.warn("AUDIT_CONTEXT_UNRESOLVED — falling back to SYSTEM: {}", e.getMessage());
            return new WriteContext(SOURCE_SYSTEM, null, null, null);
        }
    }

    /**
     * Remove control, line/paragraph separator and format characters (no forged log lines,
     * no invisible text), trim, cap at 100 chars; blank → null.
     */
    static String sanitizeDeviceId(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}\\p{Cf}]", "").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > DEVICE_ID_MAX ? cleaned.substring(0, DEVICE_ID_MAX) : cleaned;
    }

    private static boolean isAuthenticated() {
        Authentication auth = CurrentActor.authentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}

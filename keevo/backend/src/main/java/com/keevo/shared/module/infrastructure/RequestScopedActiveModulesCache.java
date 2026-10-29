package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.application.port.ActiveModulesCache;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * {@link ActiveModulesCache} bound to the current HTTP request (Story 13.2, spec-socle §5.1): the active modules
 * of a tenant are stored as an attribute of the request bound to the thread ({@link RequestContextHolder}), so
 * they live exactly as long as the request. With no request bound (schedulers, async listeners, boot) nothing is
 * cached and every lookup misses — no stale state, and unlike a {@code @RequestScope} proxy it never fails
 * outside a request. Attributes of a completed request still bound to the thread ({@code IllegalStateException})
 * are treated as a miss too.
 */
@Component
public class RequestScopedActiveModulesCache implements ActiveModulesCache {

    static final String ATTRIBUTE_PREFIX = RequestScopedActiveModulesCache.class.getName() + ".";

    @Override
    public Optional<Entry> get(String tenantSchema) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        try {
            Object cached = request.getAttribute(ATTRIBUTE_PREFIX + tenantSchema, RequestAttributes.SCOPE_REQUEST);
            return cached instanceof Entry entry ? Optional.of(entry) : Optional.empty();
        } catch (IllegalStateException finishedRequest) {
            return Optional.empty(); // attributes of a completed request still bound to the thread: a miss
        }
    }

    @Override
    public void put(String tenantSchema, Entry entry) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return;
        }
        try {
            request.setAttribute(ATTRIBUTE_PREFIX + tenantSchema, entry, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException finishedRequest) {
            // completed request: nothing to cache into
        }
    }

    @Override
    public void evict(String tenantSchema) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return;
        }
        try {
            request.removeAttribute(ATTRIBUTE_PREFIX + tenantSchema, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException finishedRequest) {
            // completed request: nothing cached any more
        }
    }
}

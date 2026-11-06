package com.keevo.crm.contact.adapter.out.store;

import com.keevo.crm.contact.application.service.ClientVisibleStores;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 — {@link ClientVisibleStores} memoized on the current HTTP request (same technique as
 * {@code RequestScopedActiveModulesCache}): every operation of a sync push batch, or every check of one REST call,
 * shares one stores query per (tenant, store). Without a request bound, every call reads the store module.
 */
@Component
class RequestScopedClientVisibleStores implements ClientVisibleStores {

    static final String ATTRIBUTE_PREFIX = RequestScopedClientVisibleStores.class.getName() + ".";

    private final StoreQueryPort storeQuery;

    RequestScopedClientVisibleStores(StoreQueryPort storeQuery) {
        this.storeQuery = storeQuery;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<UUID> of(String tenantSchema, UUID storeId) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String key = ATTRIBUTE_PREFIX + tenantSchema + "." + storeId;
        if (request != null) {
            try {
                Object cached = request.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
                if (cached instanceof Set<?> set) return (Set<UUID>) set;
            } catch (IllegalStateException finishedRequest) {
                request = null;
            }
        }
        Set<UUID> visible = Set.copyOf(storeQuery.clientVisibleStoreIds(tenantSchema, storeId));
        if (request != null) {
            try {
                request.setAttribute(key, visible, RequestAttributes.SCOPE_REQUEST);
            } catch (IllegalStateException finishedRequest) {
                // completed request: nothing to cache into
            }
        }
        return visible;
    }
}

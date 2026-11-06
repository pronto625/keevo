package com.keevo.crm.contact.adapter.out.store;

import com.keevo.store.api.StoreQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Story 20.2 — one stores query per (tenant, store) per request, e.g. for a whole sync push batch. */
class RequestScopedClientVisibleStoresTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void memoizedWithinARequest_perStore() {
        StoreQueryPort port = mock(StoreQueryPort.class);
        when(port.clientVisibleStoreIds("kv_t", A)).thenReturn(Set.of(A, B));
        when(port.clientVisibleStoreIds("kv_t", B)).thenReturn(Set.of(B));
        var memo = new RequestScopedClientVisibleStores(port);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        for (int i = 0; i < 5; i++) assertThat(memo.of("kv_t", A)).containsExactlyInAnyOrder(A, B);
        assertThat(memo.of("kv_t", B)).containsExactly(B);

        verify(port, times(1)).clientVisibleStoreIds("kv_t", A);
        verify(port, times(1)).clientVisibleStoreIds("kv_t", B);

        // a new request reads again
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        memo.of("kv_t", A);
        verify(port, times(2)).clientVisibleStoreIds("kv_t", A);
    }

    @Test
    void withoutRequest_everyCallReads() {
        StoreQueryPort port = mock(StoreQueryPort.class);
        when(port.clientVisibleStoreIds("kv_t", A)).thenReturn(Set.of(A));
        var memo = new RequestScopedClientVisibleStores(port);

        memo.of("kv_t", A);
        memo.of("kv_t", A);

        verify(port, times(2)).clientVisibleStoreIds("kv_t", A);
    }
}

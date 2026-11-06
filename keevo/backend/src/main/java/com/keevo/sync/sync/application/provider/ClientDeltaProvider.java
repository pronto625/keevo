package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Page;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Delta of {@code clients}. Story 20.2 (ADR-08) — a restricted pull (store member) only gets the clients visible from
 * its store: {@code store_id IS NULL} (legacy clients, tenant-wide) or in the store's visible set (its store, plus every
 * sharing store when it shares) — the visible set is read once per pull through the store module's API; an
 * unrestricted pull (OWNER) is unchanged.
 */
@Component
public class ClientDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code clients} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "updated_at";

    /** Named parameter: the visible store ids as a PostgreSQL array literal ({@code {id,…}}). */
    static final String VISIBLE_PARAM = "visibleStoreIds";

    /** Story 20.2 — the client visibility rule in SQL (bound parameter, constant text). */
    static final String VISIBILITY_FILTER =
            "store_id IS NULL OR store_id = ANY(CAST(:" + VISIBLE_PARAM + " AS uuid[]))";

    static final String COLUMNS = "id, name, phone, email, notes, archived, created_at, updated_at,"
            + " client_type, niu, rccm, address, contact_name, website, birth_date, store_id, phone_normalized,"
            + " marketing_consent_at, marketing_opt_out_at, marketing_consent_by";

    private final EntityManager em;
    private final StoreQueryPort storeQuery;

    public ClientDeltaProvider(EntityManager em, StoreQueryPort storeQuery) {
        this.em = em;
        this.storeQuery = storeQuery;
    }

    @Override
    public String moduleId() {
        return "crm"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "clients";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        return queryDeltaPage(since, after, limit, PullScope.NONE); // fail closed: the pull always passes its scope
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit, PullScope scope) {
        DeltaPageQuery.Rows page;
        if (scope != null && !scope.restricted()) {
            page = DeltaPageQuery.fetch(em, COLUMNS, "clients", CURSOR, since, after, limit);
        } else {
            Set<UUID> visible;
            if (scope == null || scope.storeId() == null) {
                visible = Set.of();
            } else if (scope.clientStoreIds() != null) {
                visible = scope.clientStoreIds(); // resolved once per pull by the pull service
            } else {
                visible = storeQuery.clientVisibleStoreIds(TenantContext.getCurrentTenant(), scope.storeId());
            }
            page = DeltaPageQuery.fetch(em, COLUMNS, "clients", CURSOR, since, after, limit,
                    VISIBILITY_FILTER, Map.of(VISIBLE_PARAM, arrayLiteral(visible)));
        }
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    /** {@code {id1,id2}} — UUIDs only (never caller text), so the literal needs no escaping. */
    static String arrayLiteral(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(",", "{", "}"));
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("name", str(row[1]));
        map.put("phone", str(row[2]));
        map.put("email", str(row[3]));
        map.put("notes", str(row[4]));
        map.put("archived", row[5]);
        map.put("createdAt", ts(row[6]));
        map.put("updatedAt", ts(row[7]));
        // Story 20.2 — additive Bref fields, store, consent
        map.put("clientType", str(row[8]));
        map.put("niu", str(row[9]));
        map.put("rccm", str(row[10]));
        map.put("address", str(row[11]));
        map.put("contactName", str(row[12]));
        map.put("website", str(row[13]));
        map.put("birthDate", str(row[14]));
        map.put("storeId", str(row[15]));
        map.put("phoneNormalized", str(row[16]));
        map.put("marketingConsentAt", ts(row[17]));
        map.put("marketingOptOutAt", ts(row[18]));
        map.put("marketingConsentBy", str(row[19]));
        map.put("marketingEligible", row[17] != null && row[18] == null && !Boolean.TRUE.equals(row[5]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}

package com.keevo.store.store.domain.model;

import com.keevo.store.api.StoreType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoreClientSharing — stores whose clients a member sees (Story 20.2, ADR-08)")
class StoreClientSharingTest {

    private static final Instant T = Instant.parse("2026-11-01T08:00:00Z");
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static Store store(UUID id, boolean share) {
        return new Store(id, "S" + id.toString().substring(0, 4), StoreType.STORE, null, null, true, T, T)
                .withShareClients(share);
    }

    @Test
    void notSharing_itselfOnly() {
        assertThat(StoreClientSharing.visibleStoreIds(A, List.of(store(A, false), store(B, true))))
                .containsExactly(A);
    }

    @Test
    void sharing_everySharingStore_notTheOthers() {
        var stores = List.of(store(A, true), store(B, true), store(C, false));
        assertThat(StoreClientSharing.visibleStoreIds(A, stores)).containsExactlyInAnyOrder(A, B);
        assertThat(StoreClientSharing.visibleStoreIds(C, stores)).containsExactly(C);
    }

    @Test
    void unknownOrInactiveStore_nothing() {
        assertThat(StoreClientSharing.visibleStoreIds(A, List.of(store(B, true)))).isEmpty();
        assertThat(StoreClientSharing.visibleStoreIds(A, List.of(store(A, true).withActive(false, T)))).isEmpty();
        assertThat(StoreClientSharing.visibleStoreIds(null, List.of(store(A, true)))).isEmpty();
    }

    @Test
    void shareClientsSurvivesTheWithers() {
        Store s = store(A, true);
        assertThat(s.withActive(true, T).shareClients()).isTrue();
        assertThat(s.withContact("N", null, null, T).shareClients()).isTrue();
        assertThat(s.withProfile(null, null, null, null, "CM").shareClients()).isTrue();
        assertThat(s.withLogoPath(null, T).shareClients()).isTrue();
    }

    @Test
    void profileRules_patchShareClientsByPresence_andAuditIt() {
        Store s = store(A, false);
        Store shared = StoreProfileRules.apply(s,
                new com.keevo.store.api.StoreProfilePatch(null, null, null, null, null, true));
        assertThat(shared.shareClients()).isTrue();
        assertThat(StoreProfileRules.apply(shared, com.keevo.store.api.StoreProfilePatch.NONE).shareClients()).isTrue();
        assertThat(StoreProfileRules.apply(shared,
                new com.keevo.store.api.StoreProfilePatch("other", null, null, null, null)).shareClients()).isTrue();
        var changes = StoreProfileRules.changes(s, shared);
        assertThat(changes.before()).containsEntry("shareClients", false);
        assertThat(changes.after()).containsEntry("shareClients", true);
    }
}

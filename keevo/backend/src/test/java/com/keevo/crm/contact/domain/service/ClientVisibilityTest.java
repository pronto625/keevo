package com.keevo.crm.contact.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientVisibility — one visibility rule (Story 20.2, ADR-08)")
class ClientVisibilityTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Test
    void ownerWithoutFilter_seesEverything() {
        var scope = ClientVisibility.scope(true, null, Set.of(A));
        assertThat(scope.restricted()).isFalse();
        assertThat(scope.canSee(A)).isTrue();
        assertThat(scope.canSee(C)).isTrue();
        assertThat(scope.canSee(null)).isTrue();
    }

    @Test
    void ownerWithFilter_seesTheStoreSetAndLegacy() {
        var scope = ClientVisibility.scope(true, C, Set.of(C));
        assertThat(scope.canSee(C)).isTrue();
        assertThat(scope.canSee(null)).isTrue();
        assertThat(scope.canSee(A)).isFalse();
    }

    @Test
    void memberNotSharing_seesItsStoreAndLegacy() {
        var scope = ClientVisibility.scope(false, null, Set.of(A));
        assertThat(scope.canSee(A)).isTrue();
        assertThat(scope.canSee(null)).isTrue();
        assertThat(scope.canSee(B)).isFalse();
    }

    @Test
    void memberSharing_seesEverySharingStore() {
        var scope = ClientVisibility.scope(false, null, Set.of(A, B));
        assertThat(scope.canSee(A)).isTrue();
        assertThat(scope.canSee(B)).isTrue();
        assertThat(scope.canSee(C)).isFalse();
    }

    @Test
    void memberWithoutStore_seesLegacyOnly() {
        var scope = ClientVisibility.scope(false, null, Set.of());
        assertThat(scope.canSee(null)).isTrue();
        assertThat(scope.canSee(A)).isFalse();
        assertThat(new ClientVisibility.Scope(true, null).visibleStoreIds()).isEmpty();
    }
}

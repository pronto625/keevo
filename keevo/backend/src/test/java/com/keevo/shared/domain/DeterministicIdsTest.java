package com.keevo.shared.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeterministicIdsTest — v1s-13-13: RFC 4122 v5 ids shared with the app
 * (the app's {@code test/features/pos/data/datasource/local_sale_datasource_test.dart},
 * test 'cross-language vectors match the backend DeterministicIds', pins the same vectors).
 * Vectors computed with Python {@code uuid.uuid5}.
 */
class DeterministicIdsTest {

    private static final UUID ITEM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CORRECTION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void saleCancelled_matchesCrossLanguageVector() {
        assertThat(DeterministicIds.saleCancelledMovementId(ITEM))
                .isEqualTo(UUID.fromString("953756a8-12bc-54a0-b3dc-144901d67c83"));
    }

    @Test
    void saleCorrection_matchesCrossLanguageVector() {
        assertThat(DeterministicIds.saleCorrectionMovementId(CORRECTION, ITEM))
                .isEqualTo(UUID.fromString("3a397c41-8198-5a4f-8f09-9b25c36f87ac"));
    }

    @Test
    void uuidV5_matchesRfc4122DnsExample() {
        // Python: uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")
        assertThat(DeterministicIds.uuidV5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org"))
                .isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
    }

    @Test
    void ids_areVersion5_ietfVariant_deterministic_andDistinctPerInput() {
        UUID a = DeterministicIds.saleCancelledMovementId(ITEM);
        assertThat(a.version()).isEqualTo(5);
        assertThat(a.variant()).isEqualTo(2);
        assertThat(DeterministicIds.saleCancelledMovementId(ITEM)).isEqualTo(a);
        assertThat(DeterministicIds.saleCancelledMovementId(UUID.randomUUID())).isNotEqualTo(a);
        // Two corrections of the same item (3→2→3) never collide.
        assertThat(DeterministicIds.saleCorrectionMovementId(UUID.randomUUID(), ITEM))
                .isNotEqualTo(DeterministicIds.saleCorrectionMovementId(UUID.randomUUID(), ITEM));
    }

    @Test
    void nullInputs_areRejected() {
        assertThatThrownBy(() -> DeterministicIds.saleCancelledMovementId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DeterministicIds.saleCorrectionMovementId(null, ITEM))
                .isInstanceOf(NullPointerException.class);
    }
}

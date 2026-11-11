package com.keevo.inventory.stock.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockReadScope — store scope of stock reads (Story 16.13 reprise)")
class StockReadScopeTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void ownerReadsEveryStore() {
        assertThat(StockReadScope.ALL.allows(a)).isTrue();
        assertThat(StockReadScope.ALL.allows(b)).isTrue();
        assertThat(StockReadScope.ALL.onlyStoreId()).isNull();
        assertThat(StockReadScope.ALL.excludesEveryStore()).isFalse();
        assertThat(StockReadScope.ALL.requireReadable(null)).isNull();
        assertThat(StockReadScope.ALL.requireReadable(b)).isEqualTo(b);
    }

    @Test
    void memberReadsOnlyOwnStore() {
        StockReadScope member = StockReadScope.store(a);
        assertThat(member.allows(a)).isTrue();
        assertThat(member.allows(b)).isFalse();
        assertThat(member.onlyStoreId()).isEqualTo(a);
        assertThat(member.requireReadable(null)).isEqualTo(a);
        assertThat(member.requireReadable(a)).isEqualTo(a);
        assertThatThrownBy(() -> member.requireReadable(b))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(((DomainException) e).getUserMessage())
                            .isEqualTo("Vous ne pouvez consulter que le stock de votre boutique.");
                });
    }

    @Test
    void memberWithoutStoreReadsNothing() {
        StockReadScope none = StockReadScope.store(null);
        assertThat(none.excludesEveryStore()).isTrue();
        assertThat(none.allows(a)).isFalse();
        assertThat(StockReadScope.NONE.excludesEveryStore()).isTrue();
        assertThatThrownBy(() -> none.requireReadable(null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> none.requireReadable(a)).isInstanceOf(DomainException.class);
    }
}

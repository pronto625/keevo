package com.keevo.sync.sync.domain.port.in;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaEntityProviderTest {

    /**
     * Concrete stub for contract testing the interface.
     */
    static class StubDeltaEntityProvider implements DeltaEntityProvider {
        @Override
        public String entityKey() {
            return "testEntities";
        }

        @Override
        public List<Map<String, Object>> queryDelta(Instant since) {
            return List.of(Map.of("id", "t1", "name", "Test"));
        }
    }

    @Test
    void provider_entityKey_returnsNonBlankString() {
        var provider = new StubDeltaEntityProvider();
        assertThat(provider.entityKey()).isNotBlank();
    }

    @Test
    void provider_queryDelta_returnsListOfMaps() {
        var provider = new StubDeltaEntityProvider();
        var result = provider.queryDelta(Instant.now());

        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsKey("id");
    }
}

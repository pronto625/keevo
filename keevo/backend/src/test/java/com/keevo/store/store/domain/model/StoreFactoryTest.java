package com.keevo.store.store.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StoreFactoryTest — TDD tests for StoreFactory (GoF: Factory). Story 3.1.
 */
@DisplayName("StoreFactory")
class StoreFactoryTest {

    @Test
    @DisplayName("create() should generate UUID and set isActive=true with defaults")
    void create_shouldSetDefaultsAndGenerateId() {
        Store store = StoreFactory.create("Ma Boutique", StoreType.STORE, null, null);

        assertThat(store.id()).isNotNull();
        assertThat(store.name()).isEqualTo("Ma Boutique");
        assertThat(store.type()).isEqualTo(StoreType.STORE);
        assertThat(store.isActive()).isTrue();
        assertThat(store.createdAt()).isNotNull();
        assertThat(store.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("create() with null name should throw VALIDATION_ERROR")
    void create_whenNameNull_shouldThrow_VALIDATION_ERROR() {
        assertThatThrownBy(() -> StoreFactory.create(null, StoreType.STORE, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("obligatoire");
    }

    @Test
    @DisplayName("create() with blank name should throw VALIDATION_ERROR")
    void create_whenNameBlank_shouldThrow_VALIDATION_ERROR() {
        assertThatThrownBy(() -> StoreFactory.create("  ", StoreType.STORE, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("obligatoire");
    }

    @Test
    @DisplayName("create() with name too short (1 char) should throw VALIDATION_ERROR")
    void create_whenNameTooShort_shouldThrow_VALIDATION_ERROR() {
        assertThatThrownBy(() -> StoreFactory.create("A", StoreType.STORE, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("create() with name too long (101 chars) should throw VALIDATION_ERROR")
    void create_whenNameTooLong_shouldThrow_VALIDATION_ERROR() {
        String longName = "A".repeat(101);
        assertThatThrownBy(() -> StoreFactory.create(longName, StoreType.STORE, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("create() with all fields should return correct Store")
    void create_withAllFields_shouldReturnCorrectStore() {
        Store store = StoreFactory.create("Centre Ville", StoreType.WAREHOUSE,
                "Rue des marchés, Yaoundé", "+237690000001");

        assertThat(store.name()).isEqualTo("Centre Ville");
        assertThat(store.type()).isEqualTo(StoreType.WAREHOUSE);
        assertThat(store.address()).isEqualTo("Rue des marchés, Yaoundé");
        assertThat(store.phone()).isEqualTo("+237690000001");
    }

    @Test
    @DisplayName("create() with null type defaults to STORE")
    void create_withNullType_shouldDefaultToSTORE() {
        Store store = StoreFactory.create("Test", null, null, null);
        assertThat(store.type()).isEqualTo(StoreType.STORE);
    }

    @Test
    @DisplayName("create() trims leading/trailing whitespace from name")
    void create_shouldTrimName() {
        Store store = StoreFactory.create("  Ma Boutique  ", StoreType.STORE, null, null);
        assertThat(store.name()).isEqualTo("Ma Boutique");
    }
}

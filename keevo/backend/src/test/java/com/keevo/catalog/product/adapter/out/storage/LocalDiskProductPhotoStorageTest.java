package com.keevo.catalog.product.adapter.out.storage;

import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage.StoredPhoto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LocalDiskProductPhotoStorage")
class LocalDiskProductPhotoStorageTest {

    private static final String TENANT_A = "kv_aaaaaa";
    private static final String TENANT_B = "kv_bbbbbb";

    @TempDir Path root;
    private LocalDiskProductPhotoStorage storage;
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage = new LocalDiskProductPhotoStorage(root);
    }

    @Test
    @DisplayName("store then read: same bytes and type, under <root>/<schema>/<id>.jpg, no part file left")
    void storeAndRead() throws Exception {
        storage.store(TENANT_A, productId, new byte[]{1, 2, 3}, "image/jpeg");

        Optional<StoredPhoto> read = storage.read(TENANT_A, productId);
        assertThat(read).isPresent();
        assertThat(read.get().content()).containsExactly(1, 2, 3);
        assertThat(read.get().contentType()).isEqualTo("image/jpeg");
        assertThat(root.resolve(TENANT_A).resolve(productId + ".jpg")).exists();
        try (var files = Files.list(root.resolve(TENANT_A))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
        }
    }

    @Test
    @DisplayName("tenant isolation: another tenant never reads the photo of the same product id")
    void tenantIsolation() throws Exception {
        storage.store(TENANT_A, productId, new byte[]{9}, "image/jpeg");

        assertThat(storage.read(TENANT_B, productId)).isEmpty();
        storage.delete(TENANT_B, productId);
        storage.deleteAll(TENANT_B);
        assertThat(storage.read(TENANT_A, productId)).isPresent();
    }

    @Test
    @DisplayName("replacing a JPEG with a WebP keeps a single photo")
    void replaceAcrossFormats() throws Exception {
        storage.store(TENANT_A, productId, new byte[]{1}, "image/jpeg");
        storage.store(TENANT_A, productId, new byte[]{2}, "image/webp");

        StoredPhoto read = storage.read(TENANT_A, productId).orElseThrow();
        assertThat(read.contentType()).isEqualTo("image/webp");
        assertThat(read.content()).containsExactly(2);
        assertThat(root.resolve(TENANT_A).resolve(productId + ".jpg")).doesNotExist();
    }

    @Test
    @DisplayName("delete removes the photo; deleting a missing photo is a no-op")
    void delete() throws Exception {
        storage.store(TENANT_A, productId, new byte[]{1}, "image/jpeg");
        storage.delete(TENANT_A, productId);
        storage.delete(TENANT_A, productId);

        assertThat(storage.read(TENANT_A, productId)).isEmpty();
    }

    @Test
    @DisplayName("purgeTenant (account deletion) removes every photo of the tenant and only them")
    void purgeTenant() throws Exception {
        UUID other = UUID.randomUUID();
        storage.store(TENANT_A, productId, new byte[]{1}, "image/jpeg");
        storage.store(TENANT_A, other, new byte[]{1}, "image/webp");
        storage.store(TENANT_B, productId, new byte[]{1}, "image/jpeg");

        storage.purgeTenant(TENANT_A);
        storage.purgeTenant(TENANT_A); // idempotent

        assertThat(root.resolve(TENANT_A)).doesNotExist();
        assertThat(storage.read(TENANT_B, productId)).isPresent();
    }

    @Test
    @DisplayName("an invalid schema (path traversal, public) is refused, never resolved")
    void invalidSchema() {
        assertThatThrownBy(() -> storage.store("../kv_aaaaaa", productId, new byte[]{1}, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("public", productId))
                .isInstanceOf(IllegalArgumentException.class);
        storage.deleteAll("..");  // never throws
        assertThat(root).isEmptyDirectory();
    }

    @Test
    @DisplayName("an unsupported content type is refused")
    void unsupportedType() {
        assertThatThrownBy(() -> storage.store(TENANT_A, productId, new byte[]{1}, "image/gif"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

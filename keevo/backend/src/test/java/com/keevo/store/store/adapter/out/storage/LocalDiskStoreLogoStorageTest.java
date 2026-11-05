package com.keevo.store.store.adapter.out.storage;

import com.keevo.shared.domain.port.out.TenantFilesPurger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LocalDiskStoreLogoStorage (Story 19.12)")
class LocalDiskStoreLogoStorageTest {

    @TempDir Path root;

    @Test
    @DisplayName("store → <root>/<schema>/<storeId>.png, read back with its type, no .part left")
    void storeAndRead() throws Exception {
        var storage = new LocalDiskStoreLogoStorage(root);
        UUID storeId = UUID.randomUUID();

        String name = storage.store("kv_abc123", storeId, new byte[]{1, 2, 3}, "image/png");

        assertThat(name).startsWith(storeId + "-").endsWith(".png").matches("[0-9a-f-]+\\.png");
        assertThat(root.resolve("kv_abc123").resolve(name)).exists();
        try (var files = Files.list(root.resolve("kv_abc123"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(name);
        }
        var logo = storage.read("kv_abc123", name).orElseThrow();
        assertThat(logo.content()).containsExactly(1, 2, 3);
        assertThat(logo.contentType()).isEqualTo("image/png");
        String again = storage.store("kv_abc123", storeId, new byte[]{9}, "image/png");
        assertThat(again).as("a new name per upload").isNotEqualTo(name);
        assertThat(storage.read("kv_abc123", name).orElseThrow().content()).as("previous file untouched")
                .containsExactly(1, 2, 3);
        String jpg = storage.store("kv_abc123", storeId, new byte[]{9}, "image/jpeg");
        assertThat(jpg).endsWith(".jpg");
        assertThat(storage.read("kv_abc123", jpg).orElseThrow().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("no cross-tenant read, no path escape: bad schema refused, odd file names read as none")
    void isolation() throws Exception {
        var storage = new LocalDiskStoreLogoStorage(root);
        UUID storeId = UUID.randomUUID();
        String name = storage.store("kv_abc123", storeId, new byte[]{1}, "image/png");

        assertThat(storage.read("kv_other1", name)).isEmpty();
        assertThat(storage.read("kv_abc123", "../kv_abc123/" + name)).isEmpty();
        assertThat(storage.read("kv_abc123", null)).isEmpty();
        assertThatThrownBy(() -> storage.read("../etc", name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("kv_abc123", storeId, new byte[]{1}, "image/gif"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete removes one file (missing = no-op); purgeTenant removes the tenant directory")
    void deleteAndPurge() throws Exception {
        var storage = new LocalDiskStoreLogoStorage(root);
        String a = storage.store("kv_abc123", UUID.randomUUID(), new byte[]{1}, "image/png");
        String b = storage.store("kv_abc123", UUID.randomUUID(), new byte[]{2}, "image/jpeg");
        String other = storage.store("kv_zzz999", UUID.randomUUID(), new byte[]{3}, "image/png");

        storage.delete("kv_abc123", a);
        storage.delete("kv_abc123", a);
        storage.delete("kv_abc123", "not-a-logo.txt");
        assertThat(storage.read("kv_abc123", a)).isEmpty();
        assertThat(storage.read("kv_abc123", b)).isPresent();

        TenantFilesPurger purger = storage;
        purger.purgeTenant("kv_abc123");
        purger.purgeTenant("kv_abc123");
        purger.purgeTenant("not a schema");
        assertThat(root.resolve("kv_abc123")).doesNotExist();
        assertThat(storage.read("kv_zzz999", other)).isPresent();
    }
}

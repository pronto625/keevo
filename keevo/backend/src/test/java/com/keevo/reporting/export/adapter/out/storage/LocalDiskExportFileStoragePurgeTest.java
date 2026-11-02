package com.keevo.reporting.export.adapter.out.storage;

import com.keevo.shared.domain.port.out.TenantFilesPurger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** Story 16.6 (RGPD) — the export storage purges a deleted tenant's directory, and only it. */
@DisplayName("LocalDiskExportFileStorage as TenantFilesPurger (Story 16.6)")
class LocalDiskExportFileStoragePurgeTest {

    @TempDir Path root;

    @Test
    @DisplayName("deletes <root>/<schema>/ recursively and keeps the other tenants' files")
    void purgesTheTenantDirectoryOnly() throws Exception {
        Path tenant = Files.createDirectories(root.resolve("kv_abc123"));
        Files.writeString(tenant.resolve("job-1.csv"), "a;b");
        Files.writeString(tenant.resolve("job-2.xlsx.part"), "partial");
        Files.createDirectories(tenant.resolve("nested"));
        Files.writeString(tenant.resolve("nested").resolve("stray.txt"), "x");
        Path other = Files.createDirectories(root.resolve("kv_zzz999"));
        Files.writeString(other.resolve("job-3.csv"), "keep");

        TenantFilesPurger purger = new LocalDiskExportFileStorage(root);
        purger.purgeTenant("kv_abc123");

        assertThat(tenant).doesNotExist();
        assertThat(other.resolve("job-3.csv")).exists();
        assertThat(root).exists();
    }

    @Test
    @DisplayName("absent directory, invalid or traversal schema: no-op, never throws, never outside the root")
    void noOpOnAbsentOrInvalidSchema() throws Exception {
        Path outside = Files.writeString(root.resolve("keep.txt"), "keep");
        LocalDiskExportFileStorage storage = new LocalDiskExportFileStorage(root);

        assertThatNoException().isThrownBy(() -> storage.purgeTenant("kv_abc123"));
        assertThatNoException().isThrownBy(() -> storage.purgeTenant(".."));
        assertThatNoException().isThrownBy(() -> storage.purgeTenant("../kv_abc123"));
        assertThatNoException().isThrownBy(() -> storage.purgeTenant(null));
        assertThatNoException().isThrownBy(() -> storage.purgeTenant(""));

        assertThat(outside).exists();
        assertThat(root).exists();
    }
}

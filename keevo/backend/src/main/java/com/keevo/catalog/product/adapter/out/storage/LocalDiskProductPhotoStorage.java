package com.keevo.catalog.product.adapter.out.storage;

import com.keevo.catalog.product.adapter.out.config.ProductPhotoProperties;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage;
import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Local-disk product photo storage — same pattern as {@code LocalDiskExportFileStorage}: files
 * live at {@code <keevo.product-photo.storage-dir>/<tenant schema>/<product uuid>.<ext>}, the
 * schema is re-validated with {@link TenantSchema#validate} on every access (no path can escape
 * the root, no cross-tenant read), and a write goes to a {@code .part} file published by an
 * atomic move so a reader never sees half a photo. S3 later is another adapter of the port.
 */
@Component
public class LocalDiskProductPhotoStorage implements ProductPhotoStorage, TenantFilesPurger {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskProductPhotoStorage.class);
    private static final String PART = ".part";
    /** Extension ↔ content type of the accepted (stored) formats. */
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "webp", "image/webp");

    private final Path root;

    @Autowired
    public LocalDiskProductPhotoStorage(ProductPhotoProperties properties) {
        this(Path.of(properties.getStorageDir()));
    }

    LocalDiskProductPhotoStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(String tenantSchema, UUID productId, byte[] content, String contentType) throws IOException {
        String extension = extensionOf(contentType);
        Path dir = tenantDir(tenantSchema);
        Files.createDirectories(dir);
        Path target = dir.resolve(productId + "." + extension);
        Path part = dir.resolve(productId + "." + extension + PART);
        Files.write(part, content);
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // One photo per product: a previous photo in the other format is removed.
        for (String other : TYPES.keySet()) {
            if (!other.equals(extension)) Files.deleteIfExists(dir.resolve(productId + "." + other));
        }
    }

    @Override
    public Optional<StoredPhoto> read(String tenantSchema, UUID productId) throws IOException {
        Path dir = tenantDir(tenantSchema);
        for (Map.Entry<String, String> type : TYPES.entrySet()) {
            Path file = dir.resolve(productId + "." + type.getKey());
            if (Files.isRegularFile(file)) {
                return Optional.of(new StoredPhoto(Files.readAllBytes(file), type.getValue()));
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String tenantSchema, UUID productId) {
        try {
            Path dir = tenantDir(tenantSchema);
            for (String extension : TYPES.keySet()) {
                Files.deleteIfExists(dir.resolve(productId + "." + extension));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("PRODUCT_PHOTO_DELETE_FAILED productId={} error={}", productId, e.getMessage());
        }
    }

    @Override
    public void deleteAll(String tenantSchema) {
        Path dir;
        try {
            dir = tenantDir(tenantSchema);
        } catch (IllegalArgumentException e) {
            log.warn("PRODUCT_PHOTO_PURGE_SKIPPED invalid tenant schema");
            return;
        }
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("PRODUCT_PHOTO_PURGE_FAILED schema={} error={}", tenantSchema, e.getMessage());
        }
    }

    /** Account deletion: every photo of the tenant goes with its schema. */
    @Override
    public void purgeTenant(String tenantSchema) {
        deleteAll(tenantSchema);
    }

    private Path tenantDir(String tenantSchema) {
        Path dir = root.resolve(TenantSchema.validate(tenantSchema)).normalize();
        if (!dir.getParent().equals(root)) throw new IllegalArgumentException("Invalid storage path");
        return dir;
    }

    private static String extensionOf(String contentType) {
        for (Map.Entry<String, String> type : TYPES.entrySet()) {
            if (type.getValue().equals(contentType)) return type.getKey();
        }
        throw new IllegalArgumentException("Unsupported photo content type");
    }
}

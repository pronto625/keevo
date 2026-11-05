package com.keevo.store.store.adapter.out.storage;

import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.store.store.adapter.out.config.StoreLogoProperties;
import com.keevo.store.store.domain.port.out.StoreLogoStorage;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-disk store logo storage (Story 19.12) — same pattern as the company logo: one file per store at
 * {@code <keevo.store-logo.storage-dir>/<tenant schema>/<store id>-<unique hex>.<png|jpg>} (a new name per upload),
 * the schema re-validated on every access and the file name checked against that pattern (no path escape, no
 * cross-tenant read), written to a unique temp file then moved atomically. Account deletion purges the tenant directory ({@link TenantFilesPurger}).
 */
@Component
public class LocalDiskStoreLogoStorage implements StoreLogoStorage, TenantFilesPurger {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStoreLogoStorage.class);
    /** Extension ↔ content type of the stored formats. */
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg");
    /** {@code <store id>-<unique hex>.<png|jpg>} (one name per upload). */
    private static final Pattern FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(-[0-9a-f]{1,32})?\\.(png|jpg)");

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Path root;

    @Autowired
    public LocalDiskStoreLogoStorage(StoreLogoProperties properties) {
        this(Path.of(properties.getStorageDir()));
    }

    public LocalDiskStoreLogoStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(String tenantSchema, UUID storeId, byte[] content, String contentType) throws IOException {
        // a unique name per upload: a concurrent or rolled-back upload never touches the committed file
        String fileName = storeId + "-" + Long.toHexString(System.currentTimeMillis())
                + String.format("%08x", RANDOM.nextInt()) + "." + extensionOf(contentType);
        Path dir = tenantDir(tenantSchema);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        Path part = Files.createTempFile(dir, ".upload-", ".part");
        try {
            Files.write(part, content);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        try {
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        return fileName;
    }

    @Override
    public Optional<StoredLogo> read(String tenantSchema, String fileName) throws IOException {
        Matcher m = fileName == null ? null : FILE_NAME.matcher(fileName);
        if (m == null || !m.matches()) return Optional.empty();
        Path file = tenantDir(tenantSchema).resolve(fileName);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(new StoredLogo(Files.readAllBytes(file), TYPES.get(m.group(2))));
    }

    @Override
    public void delete(String tenantSchema, String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) return;
        try {
            Files.deleteIfExists(tenantDir(tenantSchema).resolve(fileName));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("STORE_LOGO_DELETE_FAILED error={}", e.getMessage());
        }
    }

    /** Account deletion: every store logo of the tenant goes with the schema. */
    @Override
    public void purgeTenant(String tenantSchema) {
        Path dir;
        try {
            dir = tenantDir(tenantSchema);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("STORE_LOGO_PURGE_FAILED schema={} error={}", tenantSchema, e.getMessage());
        }
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
        throw new IllegalArgumentException("Unsupported logo content type");
    }
}

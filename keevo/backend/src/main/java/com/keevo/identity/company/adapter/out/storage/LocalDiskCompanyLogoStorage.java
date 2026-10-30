package com.keevo.identity.company.adapter.out.storage;

import com.keevo.identity.company.adapter.out.config.CompanyLogoProperties;
import com.keevo.identity.company.domain.port.out.CompanyLogoStorage;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
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

/**
 * Local-disk company logo storage (Story 14.2) — same pattern as {@code LocalDiskProductPhotoStorage}: one file per
 * tenant at {@code <keevo.company-logo.storage-dir>/<tenant schema>/logo.<png|jpg>}, the schema re-validated on every
 * access (no path escape, no cross-tenant read), written to a {@code .part} file then moved atomically.
 */
@Component
public class LocalDiskCompanyLogoStorage implements CompanyLogoStorage, TenantFilesPurger {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskCompanyLogoStorage.class);
    private static final String NAME = "logo";
    private static final String PART = ".part";
    /** Extension ↔ content type of the stored formats. */
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg");

    private final Path root;

    @Autowired
    public LocalDiskCompanyLogoStorage(CompanyLogoProperties properties) {
        this(Path.of(properties.getStorageDir()));
    }

    public LocalDiskCompanyLogoStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(String tenantSchema, byte[] content, String contentType) throws IOException {
        String extension = extensionOf(contentType);
        Path dir = tenantDir(tenantSchema);
        Files.createDirectories(dir);
        Path target = dir.resolve(NAME + "." + extension);
        Path part = dir.resolve(NAME + "." + extension + PART);
        Files.write(part, content);
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
        for (String other : TYPES.keySet()) {
            if (!other.equals(extension)) Files.deleteIfExists(dir.resolve(NAME + "." + other));
        }
    }

    @Override
    public Optional<Logo> read(String tenantSchema) throws IOException {
        Path dir = tenantDir(tenantSchema);
        // Normally one file; both after a crash between the move and the delete: the newest one wins.
        Path newest = null;
        String newestType = null;
        for (Map.Entry<String, String> type : TYPES.entrySet()) {
            Path file = dir.resolve(NAME + "." + type.getKey());
            if (Files.isRegularFile(file) && (newest == null
                    || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(newest)) > 0)) {
                newest = file;
                newestType = type.getValue();
            }
        }
        return newest == null ? Optional.empty() : Optional.of(new Logo(Files.readAllBytes(newest), newestType));
    }

    @Override
    public boolean exists(String tenantSchema) {
        try {
            Path dir = tenantDir(tenantSchema);
            return TYPES.keySet().stream().anyMatch(ext -> Files.isRegularFile(dir.resolve(NAME + "." + ext)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void delete(String tenantSchema) {
        try {
            Path dir = tenantDir(tenantSchema);
            for (String extension : TYPES.keySet()) {
                Files.deleteIfExists(dir.resolve(NAME + "." + extension));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("COMPANY_LOGO_DELETE_FAILED error={}", e.getMessage());
        }
    }

    /** Account deletion: the logo goes with the schema. */
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
            log.warn("COMPANY_LOGO_PURGE_FAILED schema={} error={}", tenantSchema, e.getMessage());
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

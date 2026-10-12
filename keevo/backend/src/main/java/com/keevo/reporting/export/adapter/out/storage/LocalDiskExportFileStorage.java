package com.keevo.reporting.export.adapter.out.storage;

import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local-disk export storage (Story v1s-18-1). Files live at
 * {@code <keevo.export.storage-dir>/<tenant schema>/<job uuid>.<ext>}; a write goes to a
 * {@code .part} file first and is published by an atomic move. Storage paths are relative
 * ({@code <schema>/<file>}) and re-validated on every read, so a path can never escape the root.
 */
@Component
public class LocalDiskExportFileStorage implements ExportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskExportFileStorage.class);
    private static final Pattern EXTENSION = Pattern.compile("^[a-z0-9]{1,8}$");
    private static final String PART = ".part";

    private final Path root;

    @Autowired
    public LocalDiskExportFileStorage(ExportProperties properties) {
        this(Path.of(properties.getStorageDir()));
    }

    LocalDiskExportFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public WriteHandle openWrite(String tenantSchema, UUID jobId, String extension) throws IOException {
        if (!EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Invalid export file extension");
        }
        Path dir = tenantDir(tenantSchema);
        Files.createDirectories(dir);
        String fileName = jobId + "." + extension;
        Path part = dir.resolve(fileName + PART);
        Path target = dir.resolve(fileName);
        OutputStream stream = new BufferedOutputStream(Files.newOutputStream(part), 64 * 1024);
        String storagePath = TenantSchema.validate(tenantSchema) + "/" + fileName;
        return new LocalWriteHandle(stream, part, target, storagePath);
    }

    @Override
    public InputStream read(String storagePath) throws IOException {
        return Files.newInputStream(resolve(storagePath));
    }

    @Override
    public boolean exists(String storagePath) {
        try {
            return Files.isRegularFile(resolve(storagePath));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void delete(String storagePath) {
        if (storagePath == null) return;
        try {
            Files.deleteIfExists(resolve(storagePath));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("EXPORT_FILE_DELETE_FAILED path={} error={}", storagePath, e.getMessage());
        }
    }

    @Override
    public void deletePartials(String tenantSchema, UUID jobId) {
        Path dir = tenantDir(tenantSchema);
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(dir, jobId + ".*" + PART)) {
            for (Path p : parts) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("EXPORT_PART_DELETE_FAILED jobId={} error={}", jobId, e.getMessage());
        }
    }

    private Path tenantDir(String tenantSchema) {
        return root.resolve(TenantSchema.validate(tenantSchema));
    }

    /** {@code <schema>/<file>} → absolute path under the root, or IllegalArgumentException. */
    private Path resolve(String storagePath) {
        if (storagePath == null) throw new IllegalArgumentException("No storage path");
        int sep = storagePath.indexOf('/');
        if (sep <= 0 || storagePath.indexOf('/', sep + 1) >= 0) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        String schema = TenantSchema.validate(storagePath.substring(0, sep));
        String file = storagePath.substring(sep + 1);
        Path resolved = root.resolve(schema).resolve(file).normalize();
        if (file.isEmpty() || file.startsWith(".") || !resolved.getParent().equals(root.resolve(schema))) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return resolved;
    }

    private static final class LocalWriteHandle implements WriteHandle {

        private final OutputStream stream;
        private final Path part;
        private final Path target;
        private final String storagePath;

        LocalWriteHandle(OutputStream stream, Path part, Path target, String storagePath) {
            this.stream = stream;
            this.part = part;
            this.target = target;
            this.storagePath = storagePath;
        }

        @Override
        public OutputStream stream() {
            return stream;
        }

        @Override
        public StoredFile commit() throws IOException {
            stream.close();
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(storagePath, Files.size(target));
        }

        @Override
        public void abort() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // closing a failed stream — the part file is deleted below
            }
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                log.warn("EXPORT_PART_DELETE_FAILED path={} error={}", part, e.getMessage());
            }
        }
    }
}

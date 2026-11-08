package com.keevo.invoicing.invoice.adapter.out.storage;

import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage;
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
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-disk signature storage (Story 20.7) — same pattern as the store logo storage: one file per upload at
 * {@code <keevo.invoice-signature.storage-dir>/<tenant schema>/<invoice id>-<client|seller>-<unique hex>.<png|jpg>}, the
 * schema re-validated on every access and the file name checked against that pattern (no path escape, no cross-tenant
 * read), written to a temp file then moved atomically. Account deletion purges the tenant directory
 * ({@link TenantFilesPurger}).
 */
@Component
public class LocalDiskInvoiceSignatureStorage implements InvoiceSignatureStorage, TenantFilesPurger {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskInvoiceSignatureStorage.class);
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg");
    private static final Pattern FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-(client|seller)-[0-9a-f]{1,32}\\.(png|jpg)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    @Autowired
    public LocalDiskInvoiceSignatureStorage(InvoiceSignatureProperties properties) {
        this(Path.of(properties.getStorageDir()));
    }

    public LocalDiskInvoiceSignatureStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(String tenantSchema, UUID invoiceId, SignatureRole role, byte[] content, String contentType)
            throws IOException {
        String fileName = invoiceId + "-" + role.name().toLowerCase(Locale.ROOT) + "-"
                + Long.toHexString(System.currentTimeMillis()) + String.format("%08x", RANDOM.nextInt()) + "."
                + extensionOf(contentType);
        Path dir = tenantDir(tenantSchema);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        Path part = Files.createTempFile(dir, ".upload-", ".part");
        try {
            Files.write(part, content);
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
    public Optional<StoredImage> read(String tenantSchema, String fileName) throws IOException {
        Matcher m = fileName == null ? null : FILE_NAME.matcher(fileName);
        if (m == null || !m.matches()) return Optional.empty();
        Path file = tenantDir(tenantSchema).resolve(fileName);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(new StoredImage(Files.readAllBytes(file), TYPES.get(m.group(2))));
    }

    @Override
    public void delete(String tenantSchema, String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) return;
        try {
            Files.deleteIfExists(tenantDir(tenantSchema).resolve(fileName));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("INVOICE_SIGNATURE_DELETE_FAILED error={}", e.getMessage());
        }
    }

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
            log.warn("INVOICE_SIGNATURE_PURGE_FAILED schema={} error={}", tenantSchema, e.getMessage());
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
        throw new IllegalArgumentException("Unsupported signature content type");
    }
}

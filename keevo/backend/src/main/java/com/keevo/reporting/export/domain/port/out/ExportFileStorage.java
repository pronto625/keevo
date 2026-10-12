package com.keevo.reporting.export.domain.port.out;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Temporary storage of background export files (Story v1s-18-1). Local disk in V1; object
 * storage later is a new adapter. Paths are opaque storage keys scoped by tenant schema.
 */
public interface ExportFileStorage {

    /** Open a write to a temporary part file; {@link WriteHandle#commit()} makes it visible. */
    WriteHandle openWrite(String tenantSchema, UUID jobId, String extension) throws IOException;

    InputStream read(String storagePath) throws IOException;

    boolean exists(String storagePath);

    /** Delete a committed file; missing file is a no-op. */
    void delete(String storagePath);

    /** Delete any leftover part file of {@code jobId} (interrupted job). */
    void deletePartials(String tenantSchema, UUID jobId);

    /** An in-progress write. */
    interface WriteHandle {

        OutputStream stream();

        /** Close the stream and atomically publish the file. */
        StoredFile commit() throws IOException;

        /** Close the stream and delete the part file. Never throws. */
        void abort();
    }

    record StoredFile(String storagePath, long size) {}
}

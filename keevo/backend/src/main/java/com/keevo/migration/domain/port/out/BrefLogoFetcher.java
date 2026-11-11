package com.keevo.migration.domain.port.out;

import java.util.Optional;

/**
 * Story 24.2 — downloads the logo of a Bref shop from its URL (https, allowed hosts only, bounded size and time).
 * Never inside a database transaction.
 */
public interface BrefLogoFetcher {

    /** Whether downloads are switched on. */
    boolean enabled();

    /** Whether the URL may be fetched (https, allowed host). */
    boolean allowed(String url);

    /** The image bytes, or empty when the download failed (status, type, size, time-out, network). */
    Optional<byte[]> fetch(String url);
}

package com.keevo.shared.module;

import java.util.Objects;

/**
 * Raw content of one {@code module.json} before validation.
 *
 * @param location  human-readable location used in error messages (classpath URL or test label)
 * @param directory name of the directory holding the manifest ({@code modules/<directory>/module.json})
 * @param content   UTF-8 JSON bytes
 */
public record ModuleManifestSource(String location, String directory, byte[] content) {

    public ModuleManifestSource {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(content, "content");
    }
}

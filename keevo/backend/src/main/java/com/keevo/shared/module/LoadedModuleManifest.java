package com.keevo.shared.module;

/**
 * A validated manifest with where it came from and its normalised JSON (stored as the {@code manifest} JSONB cache).
 *
 * @param location manifest location (classpath URL)
 * @param manifest validated model
 * @param json     the manifest document as read, re-serialised compactly
 */
public record LoadedModuleManifest(String location, ModuleManifest manifest, String json) {
}

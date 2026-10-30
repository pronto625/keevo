package com.keevo.shared.module;

/**
 * Programmatic registration of a module's {@link Extension extension contributions} (Story 13.9), handed to
 * {@link KeevoModule#registerExtensions} once at boot. Bound to the calling module. Validated exactly like the
 * {@link ContributesExtension} beans (the point is in the module manifest's {@code extensionPoints.fills}, id/label/
 * action well formed, id unique within the point), all-or-nothing at boot. The values are read once (snapshot).
 */
public interface ExtensionRegistrar {

    /** Contributes {@code contribution} to the extension point {@code pointId} (declared by another module). */
    void contribute(String pointId, Extension contribution);
}

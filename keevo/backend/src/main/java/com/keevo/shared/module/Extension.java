package com.keevo.shared.module;

/**
 * A contribution of a module to an extension point (a UI/workflow slot) declared by another module (Story 13.7,
 * spec-socle §8, MOD-FR10) — e.g. Invoicing adding « Facturer » to {@code pos.checkout.post-sale-actions}.
 *
 * <p>An implementation is a singleton bean of the contributing module annotated {@link ContributesExtension}; the
 * point is listed in that module's manifest {@code extensionPoints.fills}. Nobody injects it: the slot owner lists
 * the contributions usable for a tenant through {@link ExtensionRegistry#contributions}, which returns immutable
 * {@link ExtensionView} snapshots of the values read and validated once at startup (later changes are not seen).
 */
public interface Extension {

    /**
     * Identifier of the contribution, unique within its point: {@code <contributing module id>.<name>} matching
     * {@code ModuleManifestLoader.POINT_PATTERN} (e.g. {@code invoicing.create-invoice}).
     */
    String id();

    /** Label shown to the user, in French, with no technical data (e.g. « Facturer »). Not blank. */
    String label();

    /** Position within the slot: ascending, ties broken by module id then {@link #id()}. */
    int order();

    /**
     * Client routing key of the action (e.g. {@code invoicing.create-from-sale}): the app maps it to a screen or
     * flow; there is no backend dispatcher behind it. Same format as {@link #id()} ({@code <contributing module
     * id>.<name>}).
     */
    String action();

    /**
     * Story 14.5 — the module permission (spec-socle §9.2, e.g. {@code INVOICE_VIEW}) a user must hold to see this
     * contribution, or {@code null} (default): anyone who can see the slot. It must be declared by the contributing
     * module's manifest {@code permissions} (checked at boot). {@link ExtensionRegistry#contributions} does not filter
     * on it (tenant-level); the tenant extension endpoint filters on the caller's roles.
     */
    default String permission() {
        return null;
    }
}

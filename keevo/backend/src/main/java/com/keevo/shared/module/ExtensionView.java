package com.keevo.shared.module;

/**
 * Immutable snapshot of a validated {@link Extension} contribution, as {@link ExtensionRegistry#contributions}
 * returns it (Story 13.7): the values were read once at startup and checked; it is never the contribution bean.
 *
 * @param module id of the contributing module
 * @param id     contribution id ({@code <module>.<name>})
 * @param label  French label shown to the user
 * @param order  position within the slot
 * @param action client routing key ({@code <module>.<name>})
 */
public record ExtensionView(String module, String id, String label, int order, String action) implements Extension {
}

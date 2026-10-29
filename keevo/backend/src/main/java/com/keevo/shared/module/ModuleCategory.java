package com.keevo.shared.module;

import java.util.Arrays;
import java.util.Optional;

/** Category of a module (spec-socle §2.1): the manifest / database code is the lower-case {@link #code()}. */
public enum ModuleCategory {
    CORE("core"),
    BUSINESS("business"),
    PREMIUM("premium"),
    PLATFORM("platform");

    private final String code;

    ModuleCategory(String code) {
        this.code = code;
    }

    /** Code used in {@code module.json} and in {@code public.module_definitions.category}. */
    public String code() {
        return code;
    }

    /** The category with this exact code, if any. */
    public static Optional<ModuleCategory> fromCode(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }
}

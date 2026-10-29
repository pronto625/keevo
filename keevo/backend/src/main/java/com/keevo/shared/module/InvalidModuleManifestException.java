package com.keevo.shared.module;

import java.util.List;

/**
 * The module manifests on the classpath are invalid: thrown at boot (fail-fast). The message lists every problem,
 * each naming the manifest file and the offending field.
 */
public class InvalidModuleManifestException extends IllegalStateException {

    private final List<String> problems;

    public InvalidModuleManifestException(List<String> problems) {
        super("Invalid module manifest(s) — " + problems.size() + " problem(s):\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /** One line per problem, each starting with the manifest location (or naming the modules involved). */
    public List<String> problems() {
        return problems;
    }
}

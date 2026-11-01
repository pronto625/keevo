package com.keevo.shared.job;

/**
 * Story 16.4 — progress of a long super-admin operation (one tenant at a time): how many items are done out of how
 * many. Called from the thread running the operation; never throws back into it.
 */
@FunctionalInterface
public interface ProgressListener {

    /** Ignores every update (synchronous callers, tests). */
    ProgressListener NONE = (processed, total) -> { };

    /**
     * @param processed items done so far (0 … total)
     * @param total     items to do in all
     */
    void progress(int processed, int total);
}

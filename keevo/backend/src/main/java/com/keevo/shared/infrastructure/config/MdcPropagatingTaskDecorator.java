package com.keevo.shared.infrastructure.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MdcPropagatingTaskDecorator — copies the submitting thread's MDC (trace id, tenant id, user id…) into
 * the async task and restores the worker thread's own MDC afterwards (Story 12.4, routed from review 11).
 *
 * <p>Restoring — not clearing — matters when the task runs in the submitting thread itself (caller-runs
 * on saturation): that thread keeps its own MDC once the task is done.
 */
public class MdcPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(submitted);
            try {
                runnable.run();
            } finally {
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}

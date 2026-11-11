package com.keevo.shared.capability.notification;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Story 19.9c — one platform WhatsApp message (Parameter Object): its {@link PlatformMessageKind}, the values of its
 * Meta template variables and the complete French free text sent when the template is not approved or fails.
 *
 * @param kind       message kind (selects the Meta template)
 * @param parameters template variable values, in order ({@code {{1}}}, {@code {{2}}}…); exactly
 *                   {@code kind.parameterCount()} values ({@code null} → empty)
 * @param text       the complete free text (unchanged wording of the message before 19.9c)
 */
public record PlatformWhatsAppMessage(PlatformMessageKind kind, List<String> parameters, String text) {

    public PlatformWhatsAppMessage {
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
        Objects.requireNonNull(parameters, "parameters");
        parameters = parameters.stream().map(p -> p == null ? "" : p).toList(); // a missing value is never a crash
        if (parameters.size() != kind.parameterCount()) {
            throw new IllegalArgumentException(kind + " takes " + kind.parameterCount() + " template parameter(s), got "
                    + parameters.size());
        }
    }

    /** A kind whose only variable is the detail: the free text itself (flattened by the platform sender). */
    public static PlatformWhatsAppMessage detail(PlatformMessageKind kind, String text) {
        return new PlatformWhatsAppMessage(kind, Collections.singletonList(text), text);
    }

    /** A kind without variable (fixed template). */
    public static PlatformWhatsAppMessage fixed(PlatformMessageKind kind, String text) {
        return new PlatformWhatsAppMessage(kind, List.of(), text);
    }

    /** A kind with structured variables. */
    public static PlatformWhatsAppMessage of(PlatformMessageKind kind, String text, String... parameters) {
        return new PlatformWhatsAppMessage(kind, Arrays.asList(parameters), text);
    }
}

package com.keevo.shared.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.11 — the root cause kept for the boot-upgrade log, never in the exception message. */
@DisplayName("ModuleProvisioningException root cause (Story 19.11)")
class ModuleProvisioningExceptionTest {

    @Test
    @DisplayName("innermost cause as 'Class: first line [SQLState]'; Detail/Where lines dropped; message keeps no "
            + "schema nor cause text")
    void rootCause() {
        RuntimeException cause = new IllegalStateException("outer",
                new SQLException("ERROR: relation \"kv_abc123.x\" does not exist\n  Detail: Key (code)=(secret-value)"
                        + "\n  Where: SQL statement", "42P01"));

        ModuleProvisioningException e = new ModuleProvisioningException("messaging", "kv_abc123", "ERROR/42P01", "2",
                cause);

        assertThat(e.rootCause())
                .isEqualTo("SQLException: ERROR: relation \"kv_abc123.x\" does not exist [SQLState 42P01]");
        assertThat(e.rootCause()).doesNotContain("Detail").doesNotContain("secret-value");
        assertThat(e.getMessage()).doesNotContain("kv_abc123").doesNotContain("relation");
        assertThat(e.getCause()).isNull();
    }

    @Test
    @DisplayName("no cause → null; a long message is truncated; a cause without message keeps its class")
    void edges() {
        assertThat(new ModuleProvisioningException("m", "kv_abc123", "X", null).rootCause()).isNull();
        String longText = ModuleProvisioningException.describeRoot(new IllegalStateException("x".repeat(1000)));
        assertThat(longText).hasSize(ModuleProvisioningException.ROOT_CAUSE_MAX + 1).endsWith("…");
        assertThat(ModuleProvisioningException.describeRoot(new NullPointerException())).isEqualTo("NullPointerException");
    }

    @Test
    @DisplayName("a cause cycle longer than one ends the walk (bounded), no infinite loop")
    void causeCycle() {
        CyclingException a = new CyclingException("a");
        CyclingException b = new CyclingException("b");
        a.next = b;
        b.next = a;

        assertThat(ModuleProvisioningException.describeRoot(a)).startsWith("CyclingException: ");
    }

    /** getCause() can be overridden into a cycle that initCause refuses to build. */
    private static final class CyclingException extends RuntimeException {
        CyclingException next;

        CyclingException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }
}

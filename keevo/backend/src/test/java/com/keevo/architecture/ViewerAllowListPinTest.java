package com.keevo.architecture;

import com.keevo.shared.infrastructure.security.ViewerWriteGuardFilter;
import com.keevo.shared.infrastructure.security.ViewerWriteGuardFilter.AllowedWrite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.5 — the VIEWER self-service allow-list is pinned against the REST handlers of the application
 * ({@link EndpointEntitlementCoverageTest#scan}): every non-GET handler the filter lets a VIEWER call must be in
 * {@link #PINNED}, and every allow-list entry must be an existing handler. A new write that happens to match the
 * allow-list fails the build until it is reviewed and added here.
 */
@DisplayName("VIEWER allow-list pinned against the scanned endpoints (Story 19.5)")
class ViewerAllowListPinTest {

    /** The reviewed self-service writes a VIEWER may make ("VERB path-template"). */
    static final Set<String> PINNED = Set.of(
            "POST /api/v1/auth/change-password",
            "DELETE /api/v1/auth/sessions",
            "DELETE /api/v1/auth/sessions/{sessionId}",
            "POST /api/v1/devices/token",
            "DELETE /api/v1/devices/token",
            "POST /api/v1/web-push/subscribe",
            "DELETE /api/v1/web-push/subscribe",
            "PATCH /api/v1/notifications/read-all",
            "PATCH /api/v1/notifications/{id}/read",
            "POST /api/v1/feedback",
            "POST /api/v1/sync/push",
            // Story 21.4 — the assistant: a question reads data (tools keep the VIEWER read-only), conversations
            // are the viewer's own
            "POST /api/v1/ai/chat",
            "POST /api/v1/ai/conversations",
            "PUT /api/v1/ai/conversations/{id}",
            "DELETE /api/v1/ai/conversations/{id}",
            "DELETE /api/v1/ai/conversations/batch");

    /** Every write verb of a handler: its declared verbs, or all four for an unrestricted ({@code ANY}) mapping. */
    static List<String> writeVerbs(String verb) {
        List<String> verbs = verb.equals("ANY") ? List.of("POST", "PUT", "PATCH", "DELETE") : List.of(verb.split(","));
        return verbs.stream().filter(v -> !List.of("GET", "HEAD", "OPTIONS").contains(v)).toList();
    }

    @Test
    @DisplayName("allowed scanned writes == pinned set == allow-list (no new write slips in, no stale entry)")
    void allowListIsPinned() throws Exception {
        Set<String> allowedScanned = new TreeSet<>();
        for (EndpointEntitlementCoverageTest.Endpoint e : EndpointEntitlementCoverageTest.scan(new ArrayList<>())) {
            String sample = e.path().replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000001");
            for (String verb : writeVerbs(e.verb())) {
                if (ViewerWriteGuardFilter.isAllowed(verb, sample)) {
                    allowedScanned.add(verb + " " + e.path());
                }
            }
        }
        Set<String> allowList = new TreeSet<>();
        for (AllowedWrite a : ViewerWriteGuardFilter.ALLOWED_WRITES) {
            allowList.add(a.method() + " " + a.pattern());
        }
        assertThat(allowedScanned).as("non-GET handlers a VIEWER may call").isEqualTo(new TreeSet<>(PINNED));
        assertThat(allowList).as("ViewerWriteGuardFilter.ALLOWED_WRITES").isEqualTo(new TreeSet<>(PINNED));
    }
}

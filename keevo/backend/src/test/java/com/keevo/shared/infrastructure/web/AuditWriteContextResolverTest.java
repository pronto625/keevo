package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditWriteContextResolver (v1s-18-6)")
class AuditWriteContextResolverTest {

    private final AuditWriteContextResolver resolver = new AuditWriteContextResolver();

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static void authenticate(String role, AuthDetails details) {
        var auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("inside an HTTP request → ONLINE with the sanitized X-Device-Id header")
    void online_withSanitizedHeader() {
        bindRequest().addHeader("X-Device-Id", "dev-1\r\nX-Forged: 1");

        var ctx = resolver.resolve();

        assertThat(ctx.source()).isEqualTo("ONLINE");
        assertThat(ctx.deviceId()).isEqualTo("dev-1X-Forged: 1");
    }

    @Test
    @DisplayName("line/paragraph separators and format chars are stripped from the device id")
    void unicodeSeparatorsAndFormatChars_stripped() {
        bindRequest().addHeader("X-Device-Id", "dev\u2028-1\u2029\u200B\u202E");

        assertThat(resolver.resolve().deviceId()).isEqualTo("dev-1");
    }

    @Test
    @DisplayName("device header longer than 100 chars is truncated; blank header → null")
    void deviceHeader_truncatedOrNull() {
        bindRequest().addHeader("X-Device-Id", "a".repeat(150));
        assertThat(resolver.resolve().deviceId()).hasSize(100);

        RequestContextHolder.resetRequestAttributes();
        bindRequest().addHeader("X-Device-Id", "  \t ");
        assertThat(resolver.resolve().deviceId()).isNull();
    }

    @Test
    @DisplayName("inside AuditContext.runAsSync → SYNC with the push deviceId (header ignored)")
    void sync_usesPushDeviceId() {
        bindRequest().addHeader("X-Device-Id", "header-device");

        var ctx = AuditContext.runAsSync("push-device", resolver::resolve);

        assertThat(ctx.source()).isEqualTo("SYNC");
        assertThat(ctx.deviceId()).isEqualTo("push-device");
        assertThat(AuditContext.current()).as("context cleared after runAsSync").isEmpty();
    }

    @Test
    @DisplayName("no request, no sync context → SYSTEM, no device, no actor label")
    void system_outsideRequest() {
        var ctx = resolver.resolve();

        assertThat(ctx.source()).isEqualTo("SYSTEM");
        assertThat(ctx.deviceId()).isNull();
        assertThat(ctx.actorLabel()).isNull();
        assertThat(ctx.fallbackStoreId()).isNull();
    }

    @Test
    @DisplayName("authenticated EMPLOYEE → actor label from JWT firstName and fallback store = assigned store")
    void employee_labelAndStore() {
        bindRequest();
        UUID storeId = UUID.randomUUID();
        authenticate("EMPLOYEE", AuthDetails.employee("Awa", storeId));

        var ctx = resolver.resolve();

        assertThat(ctx.actorLabel()).isEqualTo("Awa");
        assertThat(ctx.fallbackStoreId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("authenticated OWNER without firstName → role fallback label, no store")
    void owner_fallbackLabel() {
        bindRequest();
        authenticate("OWNER", null);

        var ctx = resolver.resolve();

        assertThat(ctx.actorLabel()).isEqualTo("Le propriétaire");
        assertThat(ctx.fallbackStoreId()).isNull();
    }

    @Test
    @DisplayName("nested runAsSync restores the outer context")
    void nestedSync_restoresOuter() {
        AuditContext.runAsSync("outer", () -> {
            AuditContext.runAsSync("inner", () -> null);
            assertThat(AuditContext.current()).get()
                    .extracting(AuditContext.SyncContext::deviceId).isEqualTo("outer");
            return null;
        });
        assertThat(AuditContext.current()).isEmpty();
    }
}

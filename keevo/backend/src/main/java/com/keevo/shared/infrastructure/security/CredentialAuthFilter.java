package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.access.AccessState;
import com.keevo.shared.infrastructure.security.access.AccessStateService;
import com.keevo.shared.infrastructure.web.ErrorMessages;
import com.keevo.shared.module.FoundationUnavailableException;
import com.keevo.shared.module.ModuleProvisioner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Story 21.9 — authenticates the requests of a {@link CredentialAuthenticator} (personal access tokens of the MCP
 * server) and binds the token owner's session for exactly that request.
 *
 * <p>Template: the authenticator resolves its credential to an owner; this filter applies the server-side checks of
 * {@link JwtAuthFilter} (16-11 access state, read from the shared cache evicted cluster-wide on every change):
 * membership present and active, role still {@code OWNER}, company not deleted, credential created after the
 * revocation cutoff ({@code tokens_valid_after}), company schema (never the platform's), foundation tables ready.
 * Then it binds {@code TenantContext}, the security context ({@code ROLE_OWNER}, {@link AuthDetails}) and the MDC keys,
 * lets the authenticator refuse ({@link CredentialAuthenticator#afterAuthentication}: rate limit, module gate), runs
 * the request on this thread and clears everything in {@code finally} (no leak to the next request of a pooled
 * thread). A SUSPENDED or DELETION_PENDING company keeps its reads, as on the JWT path: the MCP tools are read-only.
 *
 * <p>The request is marked ({@link JwtAuthFilter#ALTERNATE_CREDENTIAL_ATTRIBUTE}) so the JWT filter skips it.
 * Refusals are {@code {"domainCode", "error"}} (French catalogue text), 401 with {@code WWW-Authenticate: Bearer}.
 * Never a Spring bean of type {@code Filter} (built by {@link SecurityConfig}): it runs only in the security chain.
 *
 * <p>Cost: the authenticator's lookup + the cached access state (0–1 query) + the foundation check (cached no-op).
 */
public class CredentialAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CredentialAuthFilter.class);

    private final List<CredentialAuthenticator> authenticators;
    private final AccessStateService accessStates;
    private final ModuleProvisioner moduleProvisioner;
    private final ObjectMapper objectMapper;

    public CredentialAuthFilter(List<CredentialAuthenticator> authenticators, AccessStateService accessStates,
                                ModuleProvisioner moduleProvisioner, ObjectMapper objectMapper) {
        this.authenticators = List.copyOf(authenticators);
        // without any authenticator the filter never runs: its collaborators may be absent
        this.accessStates = this.authenticators.isEmpty() ? accessStates
                : Objects.requireNonNull(accessStates, "accessStates");
        this.moduleProvisioner = this.authenticators.isEmpty() ? moduleProvisioner
                : Objects.requireNonNull(moduleProvisioner, "moduleProvisioner");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return authenticatorFor(request) == null;
    }

    private CredentialAuthenticator authenticatorFor(HttpServletRequest request) {
        for (CredentialAuthenticator a : authenticators) {
            if (a.handles(request)) {
                return a;
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CredentialAuthenticator authenticator = authenticatorFor(request);
        if (authenticator == null) {
            chain.doFilter(request, response);
            return;
        }
        // Whatever happens next, this request is not a JWT request.
        request.setAttribute(JwtAuthFilter.ALTERNATE_CREDENTIAL_ATTRIBUTE, Boolean.TRUE);
        try {
            CredentialAuthenticator.Refusal refused;
            try {
                refused = authenticate(authenticator, request);
            } catch (RuntimeException e) {
                // an unexpected failure while authenticating (lookup, entitlement read…): the documented JSON refusal
                log.error("Credential authentication failed ({})", e.getClass().getSimpleName());
                refused = CredentialAuthenticator.Refusal.of(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR");
            }
            if (refused != null) {
                write(response, refused);
                return;
            }
            // failures of the served request itself belong to the application (handlers, advice)
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            MDC.remove(JwtAuthFilter.MDC_TENANT_ID);
            MDC.remove(JwtAuthFilter.MDC_USER_ID);
        }
    }

    /** Resolves, checks and binds the owner; a refusal, or null with the session bound. */
    private CredentialAuthenticator.Refusal authenticate(CredentialAuthenticator authenticator,
                                                         HttpServletRequest request) {
        CredentialAuthenticator.Resolution resolution = authenticator.resolve(request);
        if (resolution instanceof CredentialAuthenticator.Refusal refusal) {
            return refusal;
        }
        CredentialAuthenticator.Owner owner = (CredentialAuthenticator.Owner) resolution;
        CredentialAuthenticator.Refusal refused = checkAndBind(authenticator, owner);
        return refused != null ? refused : authenticator.afterAuthentication(request, owner);
    }

    /** The JWT filter's server-side checks for an owner credential, then the session bound; a refusal or null. */
    private CredentialAuthenticator.Refusal checkAndBind(CredentialAuthenticator authenticator,
                                                         CredentialAuthenticator.Owner owner) {
        String schema;
        try {
            schema = TenantSchema.validate(owner.schema());
        } catch (IllegalArgumentException ex) {
            return unauthorized(authenticator.revokedCode());
        }
        AccessState state;
        try {
            state = accessStates.get(owner.userId(), schema);
        } catch (DataAccessException ex) {
            log.error("Access state lookup failed for a credential of tenant {}", schema, ex);
            return CredentialAuthenticator.Refusal.of(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }
        if (state == null || "DELETED".equals(state.tenantStatus()) || !state.membershipActive()) {
            return unauthorized("ACCOUNT_INACTIVE");
        }
        if ("SUSPENDED".equals(state.tenantStatus()) || "DELETION_PENDING".equals(state.tenantStatus())) {
            // the JWT path blocks every write of such a company; a credential request (always a POST, it may count
            // AI credits and audit rows) is refused alike (21.4.2 review)
            return CredentialAuthenticator.Refusal.of(HttpServletResponse.SC_FORBIDDEN,
                    "SUSPENDED".equals(state.tenantStatus()) ? "ACCOUNT_SUSPENDED" : "ACCOUNT_DELETION_PENDING");
        }
        if (!Role.OWNER.name().equals(state.role())) {
            // the credential's owner is no longer the owner of this company: it stops working
            return unauthorized(authenticator.revokedCode());
        }
        Instant tva = state.tokensValidAfter();
        if (tva != null && (owner.issuedAt() == null
                || owner.issuedAt().isBefore(tva.truncatedTo(ChronoUnit.MICROS)))) {
            return unauthorized(authenticator.revokedCode());
        }
        try {
            moduleProvisioner.ensureFoundation(schema);
        } catch (DomainException ex) {
            long retryAfter = ex instanceof FoundationUnavailableException unavailable
                    ? unavailable.retryAfterSeconds() : 30;
            return new CredentialAuthenticator.Refusal(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", null, Map.of("Retry-After", String.valueOf(retryAfter)));
        }

        TenantContext.setCurrentTenant(schema);
        MDC.put(JwtAuthFilter.MDC_TENANT_ID, schema);
        MDC.put(JwtAuthFilter.MDC_USER_ID, owner.userId().toString());
        var auth = new UsernamePasswordAuthenticationToken(owner.userId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.OWNER.name())));
        AccessState.EmployeeState employee = state.employee();
        auth.setDetails(new AuthDetails(employee != null ? employee.firstName() : null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return null;
    }

    private static CredentialAuthenticator.Refusal unauthorized(String code) {
        return new CredentialAuthenticator.Refusal(HttpServletResponse.SC_UNAUTHORIZED, code, null,
                Map.of("WWW-Authenticate", "Bearer"));
    }

    private void write(HttpServletResponse response, CredentialAuthenticator.Refusal refusal) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(refusal.status());
        refusal.headers().forEach(response::setHeader);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, String> body = new LinkedHashMap<>();
        HttpStatus status = HttpStatus.resolve(refusal.status());
        // Keevo's error envelope {error, code, domainCode} (21.4.2 review: code was missing)
        body.put("code", status != null ? status.name() : String.valueOf(refusal.status()));
        body.put("domainCode", refusal.domainCode());
        body.put("error", refusal.message() != null ? refusal.message()
                : ErrorMessages.of(refusal.domainCode(), ErrorMessages.of("UNAUTHORIZED")));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

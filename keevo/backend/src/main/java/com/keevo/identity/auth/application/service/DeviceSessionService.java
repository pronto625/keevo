package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.DeviceSessionView;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.auth.DeviceSessionClosedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 16.11 — « Appareils connectés »: lists and closes device sessions.
 *
 * <p>A session is open while one of its refresh tokens is live. Closing it revokes that refresh chain;
 * the database trigger broadcasts the change, so the device's access token is refused
 * ({@code SESSION_REVOKED}) at its next request on every instance. Every closing is audited.
 * Only the opaque session id is ever exposed — never a token hash.
 */
@Service
public class DeviceSessionService {

    private final RefreshTokenRepository refreshTokens;
    private final EmployeeRepository employees;
    private final ApplicationEventPublisher events;

    public DeviceSessionService(RefreshTokenRepository refreshTokens,
                                EmployeeRepository employees,
                                ApplicationEventPublisher events) {
        this.refreshTokens = refreshTokens;
        this.employees = employees;
        this.events = events;
    }

    /** Open sessions of {@code userId} in account {@code schema}, latest use first. */
    @Transactional(readOnly = true)
    public List<DeviceSessionView> list(UUID userId, String schema, UUID currentSessionId) {
        Map<UUID, DeviceSessionView> bySession = new LinkedHashMap<>();
        for (RefreshToken t : refreshTokens.findLiveSessions(userId, schema, Instant.now())) {
            UUID sid = t.effectiveSessionId();
            bySession.putIfAbsent(sid, new DeviceSessionView(sid, t.deviceLabel(),
                    t.lastUsedAt(), sid.equals(currentSessionId)));
        }
        return List.copyOf(bySession.values());
    }

    /**
     * Closes one session of {@code userId}.
     *
     * @throws DomainException DEVICE_SESSION_NOT_FOUND when it is not an open session of that user
     */
    @Transactional
    public void close(UUID actorId, UUID userId, String schema, UUID sessionId) {
        DeviceSessionView session = list(userId, schema, null).stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.DEVICE_SESSION_NOT_FOUND));
        String label = session.deviceLabel(); // null for sessions without a device label
        refreshTokens.revokeSession(userId, schema, sessionId);
        events.publishEvent(new DeviceSessionClosedEvent(actorId, schema, userId,
                DeviceSessionClosedEvent.Scope.ONE, label, 1, Instant.now()));
    }

    /**
     * Closes every session of {@code userId} except {@code keepSessionId}. Returns how many.
     *
     * @param requireKeep true when the caller closes its own other sessions: an unknown current
     *                    session (token issued before device sessions) is refused, otherwise the
     *                    caller's own device would be closed too
     */
    @Transactional
    public int closeOthers(UUID actorId, UUID userId, String schema, UUID keepSessionId, boolean requireKeep) {
        if (requireKeep && keepSessionId == null) {
            // « Cet appareil n'est plus connecté » — sign in again here first.
            throw new DomainException(ErrorCode.DEVICE_SESSION_NOT_FOUND,
                    "Current device session unknown: refuse closing all others");
        }
        int closed = 0;
        for (DeviceSessionView s : list(userId, schema, keepSessionId)) {
            if (!s.current()) {
                closed += refreshTokens.revokeSession(userId, schema, s.sessionId()) > 0 ? 1 : 0;
            }
        }
        if (closed > 0) {
            events.publishEvent(new DeviceSessionClosedEvent(actorId, schema, userId,
                    DeviceSessionClosedEvent.Scope.ALL_OTHERS, null, closed, Instant.now()));
        }
        return closed;
    }

    /** The user behind an employee of the current account (owner views). */
    @Transactional(readOnly = true)
    public UUID employeeUserId(UUID employeeId) {
        return employees.findById(employeeId)
                .map(Employee::getUserId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }
}

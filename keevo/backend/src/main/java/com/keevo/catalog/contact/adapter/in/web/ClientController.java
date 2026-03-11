package com.keevo.catalog.contact.adapter.in.web;

import com.keevo.catalog.contact.adapter.in.web.dto.*;
import com.keevo.catalog.contact.application.usecase.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ClientController — REST adapter for client management endpoints (Story 2.5).
 *
 * <p>All endpoints require an authenticated user (JWT set by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/v1/clients")
@PreAuthorize("hasRole('USER')")
public class ClientController {

    private final CreateClientUseCase createClientUseCase;
    private final GetClientsUseCase getClientsUseCase;
    private final GetClientProfileUseCase getClientProfileUseCase;
    private final UpdateClientUseCase updateClientUseCase;
    private final ArchiveClientUseCase archiveClientUseCase;

    public ClientController(CreateClientUseCase createClientUseCase,
                            GetClientsUseCase getClientsUseCase,
                            GetClientProfileUseCase getClientProfileUseCase,
                            UpdateClientUseCase updateClientUseCase,
                            ArchiveClientUseCase archiveClientUseCase) {
        this.createClientUseCase   = createClientUseCase;
        this.getClientsUseCase     = getClientsUseCase;
        this.getClientProfileUseCase = getClientProfileUseCase;
        this.updateClientUseCase   = updateClientUseCase;
        this.archiveClientUseCase  = archiveClientUseCase;
    }

    /** POST /api/v1/clients */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ClientResponseDto>> createClient(
            @Valid @RequestBody CreateClientRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new CreateClientUseCase.CreateClientCommand(
                request.name(), request.phone(), request.email(), request.notes(), actorId,
                TenantContext.getCurrentTenant());
        var client = createClientUseCase.execute(cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(ClientResponseDto.fromDomain(client)));
    }

    /** GET /api/v1/clients?search=&includeArchived= */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ClientResponseDto>>> listClients(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        var clients = getClientsUseCase.execute(
                new GetClientsUseCase.GetClientsQuery(search, includeArchived));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                clients.stream().map(ClientResponseDto::fromDomain).toList()));
    }

    /** GET /api/v1/clients/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ClientProfileResponseDto>> getClientProfile(
            @PathVariable UUID id) {
        try {
            var result = getClientProfileUseCase.execute(id);
            return ResponseEntity.ok(ApiResponseWrapper.ok(
                    ClientProfileResponseDto.fromResult(result)));
        } catch (DomainException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error("Client introuvable", "NOT_FOUND",
                            "CLIENT_NOT_FOUND", null));
        }
    }

    /** PATCH /api/v1/clients/{id} */
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ClientResponseDto>> updateClient(
            @PathVariable UUID id,
            @RequestBody UpdateClientRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new UpdateClientUseCase.UpdateClientCommand(
                id, request.name(), request.phone(), request.email(), request.notes(), actorId);
        var client = updateClientUseCase.execute(cmd);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ClientResponseDto.fromDomain(client)));
    }

    /** DELETE /api/v1/clients/{id} (soft delete) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archiveClient(@PathVariable UUID id) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        archiveClientUseCase.execute(id, actorId, TenantContext.getCurrentTenant());
        return ResponseEntity.noContent().build();
    }
}

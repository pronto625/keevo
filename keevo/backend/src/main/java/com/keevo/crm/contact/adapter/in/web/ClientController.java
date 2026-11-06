package com.keevo.crm.contact.adapter.in.web;

import com.keevo.crm.contact.adapter.in.CurrentClientAccess;
import com.keevo.crm.contact.adapter.in.web.dto.*;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.application.usecase.*;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ClientController — REST adapter for client management endpoints (Story 2.5).
 *
 * <p>All endpoints require an authenticated user (JWT set by JwtAuthFilter).
 *
 * <p>Story 20.2 (ADR-08) — clients per store: a store member sees and writes its store's clients (+ the sharing
 * stores, + the legacy clients without store), an OWNER every client ({@code ?storeId=} narrows the list); a client
 * out of sight is 404 {@code CLIENT_NOT_FOUND}. A creation whose phone already exists in the same store succeeds
 * with {@code meta.warnings = [{code: DUPLICATE_PHONE, existingClient: {id, name, phone, storeId}}]}. New:
 * {@code GET /duplicates} (pre-check) and {@code PUT /{id}/marketing-consent}.
 */
@RestController
@RequestMapping("/api/v1/clients")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class ClientController {

    static final String DUPLICATE_PHONE = "DUPLICATE_PHONE";

    private final CreateClientUseCase createClientUseCase;
    private final GetClientsUseCase getClientsUseCase;
    private final GetClientProfileUseCase getClientProfileUseCase;
    private final UpdateClientUseCase updateClientUseCase;
    private final ArchiveClientUseCase archiveClientUseCase;
    private final SetClientMarketingConsentUseCase consentUseCase;
    private final FindDuplicateClientsUseCase duplicatesUseCase;

    public ClientController(CreateClientUseCase createClientUseCase,
                            GetClientsUseCase getClientsUseCase,
                            GetClientProfileUseCase getClientProfileUseCase,
                            UpdateClientUseCase updateClientUseCase,
                            ArchiveClientUseCase archiveClientUseCase,
                            SetClientMarketingConsentUseCase consentUseCase,
                            FindDuplicateClientsUseCase duplicatesUseCase) {
        this.createClientUseCase   = createClientUseCase;
        this.getClientsUseCase     = getClientsUseCase;
        this.getClientProfileUseCase = getClientProfileUseCase;
        this.updateClientUseCase   = updateClientUseCase;
        this.archiveClientUseCase  = archiveClientUseCase;
        this.consentUseCase        = consentUseCase;
        this.duplicatesUseCase     = duplicatesUseCase;
    }

    /** POST /api/v1/clients */
    @RequiresEntitlement("crm")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ClientResponseDto>> createClient(
            @Valid @RequestBody CreateClientRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new CreateClientUseCase.CreateClientCommand(
                null, request.name(), request.phone(), request.email(), request.notes(), request.profile(),
                request.storeId(), CurrentClientAccess.get(), actorId, TenantContext.getCurrentTenant(), true);
        var result = storeChecked(() -> createClientUseCase.execute(cmd));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(ClientResponseDto.fromDomain(result.client()),
                        duplicateWarnings(result.duplicates())));
    }

    /** GET /api/v1/clients?search=&includeArchived=&storeId= */
    @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ClientResponseDto>>> listClients(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) UUID storeId) {
        var query = new GetClientsUseCase.GetClientsQuery(
                search, includeArchived, CurrentClientAccess.get(), storeId, TenantContext.getCurrentTenant());
        var clients = storeChecked(() -> getClientsUseCase.execute(query)); // unknown / inactive store → 400
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                clients.stream().map(ClientResponseDto::fromDomain).toList()));
    }

    /**
     * GET /api/v1/clients/duplicates?phone=&storeId= — Story 20.2: the non-archived clients of the store with this
     * phone (empty list when none). A store member always checks its own store.
     */
    @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
    @GetMapping("/duplicates")
    public ResponseEntity<ApiResponseWrapper<List<ClientResponseDto>>> findDuplicates(
            @RequestParam String phone,
            @RequestParam(required = false) UUID storeId) {
        ClientAccess access = CurrentClientAccess.get();
        var found = storeChecked(() -> duplicatesUseCase.execute(phone, storeId, access,
                TenantContext.getCurrentTenant()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(found.stream().map(ClientResponseDto::fromDomain).toList()));
    }

    /** GET /api/v1/clients/{id} */
    @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ClientProfileResponseDto>> getClientProfile(
            @PathVariable UUID id) {
        try {
            var result = getClientProfileUseCase.execute(id, CurrentClientAccess.get(),
                    TenantContext.getCurrentTenant());
            return ResponseEntity.ok(ApiResponseWrapper.ok(
                    ClientProfileResponseDto.fromResult(result)));
        } catch (DomainException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseWrapper.error("Client introuvable", "NOT_FOUND",
                            "CLIENT_NOT_FOUND", null));
        }
    }

    /** PATCH /api/v1/clients/{id} */
    @RequiresEntitlement("crm")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ClientResponseDto>> updateClient(
            @PathVariable UUID id,
            @RequestBody UpdateClientRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var cmd = new UpdateClientUseCase.UpdateClientCommand(
                id, request.name(), request.phone(), request.email(), request.notes(), actorId, request.profile(),
                request.storeId(), CurrentClientAccess.get(), TenantContext.getCurrentTenant());
        var client = storeChecked(() -> updateClientUseCase.execute(cmd));
        return ResponseEntity.ok(ApiResponseWrapper.ok(ClientResponseDto.fromDomain(client)));
    }

    /**
     * PUT /api/v1/clients/{id}/marketing-consent {@code {consent: boolean}} — Story 20.2: {@code true} records the
     * consent (now, by the actor), {@code false} withdraws it; the client's own STOP always wins.
     */
    @RequiresEntitlement("crm")
    @PutMapping("/{id}/marketing-consent")
    public ResponseEntity<ApiResponseWrapper<ClientResponseDto>> setMarketingConsent(
            @PathVariable UUID id,
            @Valid @RequestBody MarketingConsentRequestDto request) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var client = consentUseCase.execute(id, request.consent(), actorId, TenantContext.getCurrentTenant(),
                CurrentClientAccess.get());
        return ResponseEntity.ok(ApiResponseWrapper.ok(ClientResponseDto.fromDomain(client)));
    }

    /** DELETE /api/v1/clients/{id} (soft delete) */
    @RequiresEntitlement("crm")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archiveClient(@PathVariable UUID id) {
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        archiveClientUseCase.execute(id, actorId, TenantContext.getCurrentTenant(), CurrentClientAccess.get());
        return ResponseEntity.noContent().build();
    }

    /** An unknown / inactive store asked for → 400 {@code VALIDATION_ERROR} (a bad request parameter). */
    private static <T> T storeChecked(Supplier<T> call) {
        try {
            return call.get();
        } catch (DomainException e) {
            if (ClientStoreScope.isUnknownStore(e)) {
                String message = e.getUserMessage() != null ? e.getUserMessage() : e.getMessage();
                throw new InvalidRequestParameterException(ClientStoreScope.STORE_FIELD, message, message);
            }
            throw e;
        }
    }

    /** {@code {warnings: [{code: DUPLICATE_PHONE, existingClient: {id, name, phone, storeId}}]}}, null when none. */
    static Map<String, Object> duplicateWarnings(List<Client> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) return null;
        List<Map<String, Object>> warnings = duplicates.stream().map(d -> {
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("id", d.id());
            existing.put("name", d.name());
            existing.put("phone", d.phone());
            existing.put("storeId", d.storeId());
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("code", DUPLICATE_PHONE);
            warning.put("existingClient", existing);
            return warning;
        }).toList();
        return Map.of("warnings", warnings);
    }
}

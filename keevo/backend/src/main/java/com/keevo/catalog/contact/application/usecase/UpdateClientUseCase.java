package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UpdateClientUseCase — updates mutable fields on an existing client (Story 2.5).
 */
@Service
public class UpdateClientUseCase {

    private final ClientRepository clientRepository;

    public UpdateClientUseCase(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public record UpdateClientCommand(
            UUID clientId,
            String name,
            String phone,
            String email,
            String notes,
            UUID actorId
    ) {}

    public Client execute(UpdateClientCommand cmd) {
        Client existing = clientRepository.findById(cmd.clientId())
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND,
                        "clientId=" + cmd.clientId()));

        // Records are immutable — create updated version with new field values
        Client updated = new Client(
                existing.id(),
                cmd.name()  != null ? cmd.name()  : existing.name(),
                cmd.phone() != null ? cmd.phone() : existing.phone(),
                cmd.email() != null ? cmd.email() : existing.email(),
                cmd.notes() != null ? cmd.notes() : existing.notes(),
                existing.archived(),
                existing.createdAt(),
                Instant.now()
        );
        return clientRepository.save(updated);
    }
}

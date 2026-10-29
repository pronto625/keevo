package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.adapter.out.persistence.entity.ClientJpaEntity;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ClientRepositoryAdapter — JPA implementation of {@link ClientRepository} (Story 2.5).
 */
@Component
public class ClientRepositoryAdapter implements ClientRepository {

    private final ClientSpringRepository springRepository;

    public ClientRepositoryAdapter(ClientSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Client save(Client client) {
        return toDomain(springRepository.save(toEntity(client)));
    }

    @Override
    public Optional<Client> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Client> findAll(boolean includeArchived) {
        return springRepository.findAllFiltered(includeArchived)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> searchByNameOrPhone(String query) {
        return springRepository.searchByNameOrPhone(query)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void archive(UUID id) {
        springRepository.findById(id).ifPresent(e -> {
            e.setArchived(true);
            e.setUpdatedAt(Instant.now());
            springRepository.save(e);
        });
    }

    @Override
    public void unarchive(UUID id) {
        springRepository.findById(id).ifPresent(e -> {
            e.setArchived(false);
            e.setUpdatedAt(Instant.now()); // pull cursor: the other devices get the restored client
            springRepository.save(e);
        });
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ClientJpaEntity toEntity(Client client) {
        return new ClientJpaEntity(
                client.id(), client.name(), client.phone(), client.email(),
                client.notes(), client.archived(), client.createdAt(), client.updatedAt());
    }

    private Client toDomain(ClientJpaEntity e) {
        return new Client(e.getId(), e.getName(), e.getPhone(), e.getEmail(),
                e.getNotes(), e.getArchived(), e.getCreatedAt(), e.getUpdatedAt());
    }
}

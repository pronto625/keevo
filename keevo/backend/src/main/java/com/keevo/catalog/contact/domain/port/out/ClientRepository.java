package com.keevo.catalog.contact.domain.port.out;

import com.keevo.catalog.contact.domain.entity.Client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ClientRepository — output port for client persistence (Hexagonal Architecture).
 *
 * <p>Isolates domain logic from JDBC/JPA details. Implementation lives in
 * {@code catalog/contact/adapter/out/persistence/}.
 */
public interface ClientRepository {

    Client save(Client client);

    Optional<Client> findById(UUID id);

    List<Client> findAll(boolean includeArchived);

    List<Client> searchByNameOrPhone(String query);

    void archive(UUID id);
}

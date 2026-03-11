package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.shared.infrastructure.persistence.entity.ClientJpaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClientRepositoryAdapter (Story 2.5) — Mockito, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientRepositoryAdapter")
class ClientRepositoryAdapterTest {

    @Mock ClientSpringRepository springRepository;
    @InjectMocks ClientRepositoryAdapter adapter;

    private static final UUID ID  = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private ClientJpaEntity sampleEntity() {
        return new ClientJpaEntity(ID, "Alice", "+22670000001", "alice@example.com", "VIP", false, NOW, NOW);
    }

    private Client sampleDomain() {
        return new Client(ID, "Alice", "+22670000001", "alice@example.com", "VIP", false, NOW, NOW);
    }

    @Test
    @DisplayName("save() delegates to springRepository.save and maps result")
    void should_save_and_return_domain_client() {
        when(springRepository.save(any())).thenReturn(sampleEntity());

        Client result = adapter.save(sampleDomain());

        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.name()).isEqualTo("Alice");
        verify(springRepository).save(any(ClientJpaEntity.class));
    }

    @Test
    @DisplayName("findById() returns mapped domain client when entity exists")
    void should_find_by_id_and_map() {
        when(springRepository.findById(ID)).thenReturn(Optional.of(sampleEntity()));

        Optional<Client> result = adapter.findById(ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("findById() returns empty Optional when entity is absent")
    void should_return_empty_when_not_found() {
        when(springRepository.findById(ID)).thenReturn(Optional.empty());
        assertThat(adapter.findById(ID)).isEmpty();
    }

    @Test
    @DisplayName("findAll(false) delegates to findAllFiltered(false)")
    void should_delegate_find_all_to_filtered_query() {
        when(springRepository.findAllFiltered(false)).thenReturn(List.of(sampleEntity()));

        List<Client> result = adapter.findAll(false);

        assertThat(result).hasSize(1);
        verify(springRepository).findAllFiltered(false);
    }

    @Test
    @DisplayName("searchByNameOrPhone() delegates to spring repo and maps")
    void should_delegate_search() {
        when(springRepository.searchByNameOrPhone("ali")).thenReturn(List.of(sampleEntity()));

        List<Client> result = adapter.searchByNameOrPhone("ali");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("archive() sets archived=true and saves when entity found")
    void should_archive_existing_client() {
        ClientJpaEntity entity = sampleEntity();
        when(springRepository.findById(ID)).thenReturn(Optional.of(entity));

        adapter.archive(ID);

        assertThat(entity.getArchived()).isTrue();
        verify(springRepository).save(entity);
    }

    @Test
    @DisplayName("archive() is no-op when entity not found")
    void should_not_throw_when_archiving_unknown_id() {
        when(springRepository.findById(any())).thenReturn(Optional.empty());

        adapter.archive(UUID.randomUUID());

        verify(springRepository, never()).save(any());
    }
}

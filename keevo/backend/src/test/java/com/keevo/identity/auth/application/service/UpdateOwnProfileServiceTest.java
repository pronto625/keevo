package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.auth.UserProfileUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UpdateOwnProfileService — Story 22.19 (D17)")
class UpdateOwnProfileServiceTest {

    private static final String TENANT = "kv_prof01";

    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final GetUserProfileUseCase profiles = mock(GetUserProfileUseCase.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UpdateOwnProfileService service = new UpdateOwnProfileService(users, employees, profiles, events);
    private final UUID userId = UUID.randomUUID();
    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User(userId, "+237690000001", "hash", Role.OWNER, true, Instant.now(), 0, null, null, null,
                null, null, null, null);
        when(users.findById(userId)).thenReturn(Optional.of(owner));
        when(employees.findByUserId(userId)).thenReturn(Optional.empty());
    }

    private static UserProfileData profile(UUID id, String first, String last) {
        return new UserProfileData(id, "+237690000001", "OWNER", first, last, null, null, false, "ACTIVE", true,
                null);
    }

    @Test
    @DisplayName("owner: names trimmed and saved on public.users, audited before → after, GET body returned")
    void ownerRename() {
        UserProfileData after = profile(userId, "Aicha", "Mbarga");
        when(profiles.execute(userId, TENANT)).thenReturn(profile(userId, null, null), after);

        assertThat(service.update(userId, TENANT, "  Aicha ", "Mbarga")).isSameAs(after);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Aicha");
        assertThat(saved.getValue().getLastName()).isEqualTo("Mbarga");
        ArgumentCaptor<UserProfileUpdatedEvent> event = ArgumentCaptor.forClass(UserProfileUpdatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().firstNameBefore()).isNull();
        assertThat(event.getValue().firstName()).isEqualTo("Aicha");
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("store member: his employee record of this company follows (where the GET reads the name)")
    void memberRename() {
        Employee emp = Employee.create(userId, UUID.randomUUID(), "Paul", "E");
        when(employees.findByUserId(userId)).thenReturn(Optional.of(emp));
        when(profiles.execute(userId, TENANT)).thenReturn(profile(userId, "Paul", "E"),
                profile(userId, "Paul", "Etoundi"));

        service.update(userId, TENANT, "Paul", "Etoundi");

        verify(employees).updateProfile(emp.getId(), "Paul", "Etoundi");
    }

    @Test
    @DisplayName("blank or over 50 characters → French VALIDATION_ERROR, nothing written")
    void invalid() {
        assertThatThrownBy(() -> service.update(userId, TENANT, " ", "Mbarga"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("domainCode", "VALIDATION_ERROR");
        assertThatThrownBy(() -> service.update(userId, TENANT, "Aicha", "x".repeat(51)))
                .isInstanceOf(DomainException.class);
        verify(users, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("same names → no write, no audit")
    void unchanged() {
        owner = owner.withNames("Aicha", "Mbarga");
        when(users.findById(userId)).thenReturn(Optional.of(owner));
        when(profiles.execute(userId, TENANT)).thenReturn(profile(userId, "Aicha", "Mbarga"));

        service.update(userId, TENANT, "Aicha", "Mbarga");

        verify(users, never()).save(any());
        verifyNoInteractions(events);
    }
}

package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.UserProfileService;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 19.1 — {@code GET /api/v1/auth/profile} through the controller, the real {@link UserProfileService} and
 * Jackson: a phone-less account is serialised with {@code "phoneNumber":""} (the V1 Flutter app parses it as a
 * non-null string), and an OWNER's names come from {@code public.users}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — profile of a phone-less account (19.1)")
class AuthControllerProfileTest {

    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock StoreRepository storeRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock SessionTokenIssuer sessionTokenIssuer;

    MockMvc mockMvc;
    UUID userId;

    @BeforeEach
    void setUp() {
        UserProfileService profile = new UserProfileService(userRepository, employeeRepository,
                StoreApiTestSupport.queryPort(storeRepository), tenantRepository, membershipRepository,
                sessionTokenIssuer);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null, null, profile, null, null, null, null, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userId = UUID.randomUUID();
        TenantContext.setCurrentTenant("kv_prof01");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("email-only OWNER → \"phoneNumber\":\"\" and names from public.users")
    void phonelessOwner_serialisesEmptyPhone() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User(userId, null, null, Role.OWNER, true,
                Instant.now(), 0, null, "awa@example.cm", null, "Awa", "Ndiaye", Instant.now(), null)));
        when(tenantRepository.findBySchemaName("kv_prof01")).thenReturn(Optional.empty());
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/profile"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"phoneNumber\":\"\"")))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.firstName").value("Awa"))
                .andExpect(jsonPath("$.lastName").value("Ndiaye"));
    }

    @Test
    @DisplayName("19.5 — a MANAGER's profile: role EMPLOYEE (V1), memberRole MANAGER, his store")
    void managerProfile_v1Vocabulary() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User(userId, "+237690000009", "h",
                Role.MANAGER, true, Instant.now())));
        when(tenantRepository.findBySchemaName("kv_prof01")).thenReturn(Optional.of(new com.keevo.identity.auth.domain.model.Tenant(
                tenantId, "KV-PROF01", "kv_prof01", "Boutique", com.keevo.identity.api.TenantStatus.ACTIVE,
                com.keevo.identity.api.PlanType.FREE, Instant.now())));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(
                com.keevo.identity.auth.domain.model.UserTenantMembership.create(userId, tenantId, "MANAGER")));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(new com.keevo.identity.employee.domain.model.Employee(
                UUID.randomUUID(), userId, store, "Awa", "Kamga",
                com.keevo.identity.employee.domain.model.EmployeeStatus.ACTIVE, false, Instant.now())));

        mockMvc.perform(get("/api/v1/auth/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.memberRole").value("MANAGER"))
                .andExpect(jsonPath("$.storeId").value(store.toString()));
    }
}

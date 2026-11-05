package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Story 19.5 — the MANAGER team rule on {@code /api/v1/employees}: only the EMPLOYEE/VIEWER members of his own store,
 * creation in his store as EMPLOYEE or VIEWER only, never a role change nor a store reassignment; the list is
 * store-filtered in the query. V1 vocabulary: a MANAGER member shows as {@code role: EMPLOYEE},
 * {@code memberRole: MANAGER}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeController — MANAGER team rule (Story 19.5)")
class EmployeeControllerManagerTest {

    @Mock CreateEmployeeUseCase createEmployeeUseCase;
    @Mock ListEmployeesUseCase listEmployeesUseCase;
    @Mock ReassignStoreUseCase reassignStoreUseCase;
    @Mock DeactivateEmployeeUseCase deactivateEmployeeUseCase;
    @Mock ReactivateEmployeeUseCase reactivateEmployeeUseCase;
    @Mock RegeneratePasswordUseCase regeneratePasswordUseCase;
    @Mock UpdateEmployeeUseCase updateEmployeeUseCase;
    @Mock ChangeEmployeeRoleUseCase changeEmployeeRoleUseCase;
    @Mock SetEmployeePasswordUseCase setEmployeePasswordUseCase;
    @Mock EmployeeRoleResolver roleResolver;
    @Mock GetEmployeeActivityUseCase activityUseCase;
    @Mock EmployeeRepository employeeRepository;

    MockMvc mockMvc;
    final UUID managerId = UUID.randomUUID();
    final UUID myStore = UUID.randomUUID();
    final UUID otherStore = UUID.randomUUID();
    Employee seller;       // EMPLOYEE of my store
    Employee otherSeller;  // EMPLOYEE of another store
    Employee colleague;    // MANAGER of my store
    Employee ownerRow;     // OWNER with an employee row in my store
    Employee ghost;        // employee row of my store without membership
    Employee self;         // the MANAGER himself

    @BeforeEach
    void setUp() {
        EmployeeController controller = new EmployeeController(createEmployeeUseCase, listEmployeesUseCase,
                reassignStoreUseCase, deactivateEmployeeUseCase, reactivateEmployeeUseCase, regeneratePasswordUseCase,
                updateEmployeeUseCase, changeEmployeeRoleUseCase, setEmployeePasswordUseCase, roleResolver,
                activityUseCase, new EmployeeTeamScope(employeeRepository, roleResolver));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        actAs("ROLE_MANAGER", myStore);

        seller = employee(myStore);
        otherSeller = employee(otherStore);
        colleague = employee(myStore);
        ownerRow = employee(myStore);
        ghost = employee(myStore);
        self = new Employee(UUID.randomUUID(), managerId, myStore, "Gérant", "Moi", EmployeeStatus.ACTIVE, false,
                Instant.now());
        Map<UUID, String> roles = Map.of(seller.getUserId(), "EMPLOYEE", otherSeller.getUserId(), "EMPLOYEE",
                colleague.getUserId(), "MANAGER", ownerRow.getUserId(), "OWNER", managerId, "MANAGER");
        for (Employee e : List.of(seller, otherSeller, colleague, ownerRow, ghost, self)) {
            lenient().when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
            lenient().when(roleResolver.membershipRole(e.getUserId()))
                    .thenReturn(Optional.ofNullable(roles.get(e.getUserId())));
        }
        lenient().doAnswer(inv -> {
            Map<UUID, EmployeeRoleResolver.Enrichment> map = new HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                String role = roles.get((UUID) id);
                map.put((UUID) id, new EmployeeRoleResolver.Enrichment(role != null ? role : "EMPLOYEE", "", null,
                        role != null));
            }
            return map;
        }).when(roleResolver).resolveAll(any());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String authority, UUID store) {
        var auth = new UsernamePasswordAuthenticationToken(managerId, null, List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(new AuthDetails("Gérant", store, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Employee employee(UUID store) {
        return new Employee(UUID.randomUUID(), UUID.randomUUID(), store, "Awa", "Kamga", EmployeeStatus.ACTIVE, false,
                Instant.now());
    }

    private static com.keevo.identity.employee.domain.model.EmployeeActivity activity(Employee e) {
        return new com.keevo.identity.employee.domain.model.EmployeeActivity(e.getId(), e.getUserId(), null, null, 0,
                0L, 0, 0L, false, false);
    }

    private String createBody(UUID store, String role) {
        return """
                {"firstName":"Awa","lastName":"Kamga","username":"awa_k","storeId":"%s"%s}
                """.formatted(store, role == null ? "" : ",\"role\":\"" + role + "\"");
    }

    @Test
    @DisplayName("GET /employees: store-filtered query, then only EMPLOYEE/VIEWER members and himself (no OWNER, other "
            + "MANAGER, nor member without membership), one bulk enrichment; himself: role EMPLOYEE + memberRole MANAGER")
    void listIsStoreFiltered() throws Exception {
        when(listEmployeesUseCase.execute(any())).thenReturn(List.of(seller, colleague, ownerRow, ghost, self));

        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(seller.getId().toString()))
                .andExpect(jsonPath("$.data[0].memberRole").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data[1].id").value(self.getId().toString()))
                .andExpect(jsonPath("$.data[1].role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data[1].memberRole").value("MANAGER"));

        ArgumentCaptor<ListEmployeesQuery> query = ArgumentCaptor.forClass(ListEmployeesQuery.class);
        verify(listEmployeesUseCase).execute(query.capture());
        assertThat(query.getValue().storeId()).isEqualTo(myStore);
        verify(roleResolver, times(1)).resolveAll(any());
        verify(roleResolver, never()).membershipRole(any());
    }

    @Test
    @DisplayName("an OWNER's list is not store-filtered (unchanged)")
    void ownerListUnfiltered() throws Exception {
        actAs("ROLE_OWNER", null);
        when(listEmployeesUseCase.execute(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/employees")).andExpect(status().isOk());
        ArgumentCaptor<ListEmployeesQuery> query = ArgumentCaptor.forClass(ListEmployeesQuery.class);
        verify(listEmployeesUseCase).execute(query.capture());
        assertThat(query.getValue().storeId()).isNull();
    }

    @Test
    @DisplayName("creates an EMPLOYEE (default) or a VIEWER in his store → 201, the role reaches the command")
    void createsInOwnStore() throws Exception {
        when(createEmployeeUseCase.execute(any())).thenReturn(new CreateEmployeeResult(seller, "TmpPass12345"));

        mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(myStore, null))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(myStore, "VIEWER"))).andExpect(status().isCreated());

        ArgumentCaptor<CreateEmployeeCommand> cmd = ArgumentCaptor.forClass(CreateEmployeeCommand.class);
        verify(createEmployeeUseCase, times(2)).execute(cmd.capture());
        assertThat(cmd.getAllValues()).extracting(CreateEmployeeCommand::role).containsExactly(null, "VIEWER");
    }

    @Test
    @DisplayName("creating in another store, or a MANAGER / OWNER → 403 FORBIDDEN, nothing created")
    void createOutsideTeamForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(otherStore, "EMPLOYEE")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(myStore, "MANAGER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(myStore, "OWNER"))).andExpect(status().isForbidden());
        verifyNoInteractions(createEmployeeUseCase);
    }

    @Test
    @DisplayName("role change and store reassignment stay OWNER-only → 403")
    void roleAndStoreChangesForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/employees/" + seller.getId() + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/employees/" + seller.getId() + "/store").contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":\"" + otherStore + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/employees/" + seller.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":\"" + otherStore + "\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(changeEmployeeRoleUseCase, reassignStoreUseCase, updateEmployeeUseCase);
    }

    @Test
    @DisplayName("manages an EMPLOYEE of his store: profile, deactivate, reactivate, password, activity → 2xx")
    void managesOwnTeam() throws Exception {
        when(updateEmployeeUseCase.execute(any())).thenReturn(seller);
        when(regeneratePasswordUseCase.execute(any())).thenReturn(new CreateEmployeeResult(seller, "TmpPass12345"));

        mockMvc.perform(patch("/api/v1/employees/" + seller.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Awa\",\"storeId\":\"" + myStore + "\"}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/employees/" + seller.getId() + "/deactivate")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/employees/" + seller.getId() + "/reactivate")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/employees/" + seller.getId() + "/regenerate-password"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/employees/" + seller.getId() + "/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"Secret1234\"}")).andExpect(status().isOk());
        verify(deactivateEmployeeUseCase).execute(any());
        verify(reactivateEmployeeUseCase).execute(any());
        verify(setEmployeePasswordUseCase).execute(any());
    }

    @Test
    @DisplayName("another store's member, a MANAGER colleague, an OWNER of his store or a member without membership "
            + "→ 403 on every per-member endpoint")
    void outsideTeamForbidden() throws Exception {
        for (Employee target : List.of(otherSeller, colleague, ownerRow, ghost)) {
            String base = "/api/v1/employees/" + target.getId();
            mockMvc.perform(patch(base + "/deactivate")).andExpect(status().isForbidden());
            mockMvc.perform(patch(base + "/reactivate")).andExpect(status().isForbidden());
            mockMvc.perform(post(base + "/regenerate-password")).andExpect(status().isForbidden());
            mockMvc.perform(post(base + "/password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newPassword\":\"Secret1234\"}")).andExpect(status().isForbidden());
            mockMvc.perform(patch(base).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"X\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(base + "/activity")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(deactivateEmployeeUseCase, reactivateEmployeeUseCase, regeneratePasswordUseCase,
                setEmployeePasswordUseCase, updateEmployeeUseCase, activityUseCase);
    }

    @Test
    @DisplayName("GET /employees/activity: his store only (forStore), OWNER/MANAGER rows filtered out")
    void activityIsStoreFiltered() throws Exception {
        when(activityUseCase.forStore(myStore)).thenReturn(List.of(activity(seller), activity(ownerRow),
                activity(colleague)));
        mockMvc.perform(get("/api/v1/employees/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].employeeId").value(seller.getId().toString()));
        verify(activityUseCase).forStore(myStore);
        verify(activityUseCase, never()).forAll();
    }

    @Test
    @DisplayName("EMPLOYEE and VIEWER have no team management → 403")
    void othersForbidden() throws Exception {
        for (String role : List.of("ROLE_EMPLOYEE", "ROLE_VIEWER")) {
            actAs(role, myStore);
            mockMvc.perform(get("/api/v1/employees")).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(myStore, null))).andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/v1/employees/" + seller.getId() + "/deactivate"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(listEmployeesUseCase, createEmployeeUseCase, deactivateEmployeeUseCase);
    }
}

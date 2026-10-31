package com.keevo.invoicing.invoice.adapter.in.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.keevo.invoicing.invoice.application.usecase.InvoiceLifecycleUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.application.ModulePermissions;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceSyncHandler — offline invoice operations (Story 15.5b)")
class InvoiceSyncHandlerTest {

    private static final String TENANT = "kv_test01";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Set<String> ALL = Set.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_SEND", "INVOICE_MANAGE");
    private static final Set<String> EMPLOYEE = Set.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_SEND");

    private final ManageDraftInvoiceUseCase drafts = mock(ManageDraftInvoiceUseCase.class);
    private final InvoiceLifecycleUseCase lifecycle = mock(InvoiceLifecycleUseCase.class);
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private final ModulePermissions permissions = mock(ModulePermissions.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private InvoiceSyncHandler handler;

    private final UUID invoiceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new InvoiceSyncHandler(drafts, lifecycle, provisioner, permissions, objectMapper, validator);
        as("OWNER", ALL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void as(String role, Set<String> held) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(permissions.permissionsOf(anyCollection())).thenReturn(held);
    }

    private static SyncOperation op(String type, Map<String, Object> payload) {
        return new SyncOperation(UUID.randomUUID().toString(), type, null, payload, Instant.now());
    }

    private Invoice invoice() {
        return Invoice.draft(invoiceId, new ClientInfo(null, "Société Ndolo", null, null, null),
                List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), InvoicingSettings.defaults(),
                ACTOR, Instant.now());
    }

    private Map<String, Object> content() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("client", Map.of("name", "Société Ndolo", "phone", "+237690000001"));
        p.put("lines", List.of(line));
        return p;
    }

    @Test
    @DisplayName("five operation types, owned by the invoicing module")
    void contract() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("CREATE_INVOICE", "UPDATE_INVOICE",
                "ISSUE_INVOICE", "PAY_INVOICE", "CANCEL_INVOICE");
        assertThat(handler.moduleId()).isEqualTo("invoicing");
    }

    @Test
    @DisplayName("CREATE_INVOICE: the device id and content reach the use case; tables provisioned first")
    void create() {
        when(drafts.create(eq(invoiceId), any(), eq(ACTOR), eq(TENANT))).thenReturn(invoice());
        Map<String, Object> p = content();
        p.put("id", invoiceId.toString());

        SyncOperationResult r = handler.handle(op("CREATE_INVOICE", p), ACTOR, TENANT);

        assertThat(r.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(r.serverEntityId()).isEqualTo(invoiceId.toString());
        ArgumentCaptor<ManageDraftInvoiceUseCase.DraftContent> content =
                ArgumentCaptor.forClass(ManageDraftInvoiceUseCase.DraftContent.class);
        verify(drafts).create(eq(invoiceId), content.capture(), eq(ACTOR), eq(TENANT));
        assertThat(content.getValue().client().name()).isEqualTo("Société Ndolo");
        assertThat(content.getValue().lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("2"));
        verify(provisioner).ensureProvisioned(TENANT, "invoicing");
    }

    @Test
    @DisplayName("CREATE_INVOICE without id or with a blank line: rejected with VALIDATION / missing id, nothing written")
    void createInvalid() {
        SyncOperationResult noId = handler.handle(op("CREATE_INVOICE", content()), ACTOR, TENANT);
        assertThat(noId.status()).isEqualTo(SyncOperationStatus.REJECTED);

        Map<String, Object> p = content();
        p.put("id", invoiceId.toString());
        p.put("lines", List.of(Map.of("description", " ", "quantity", 1, "unitPrice", 10)));
        SyncOperationResult blank = handler.handle(op("CREATE_INVOICE", p), ACTOR, TENANT);
        assertThat(blank.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(blank.reason()).isEqualTo("VALIDATION_ERROR");

        p.put("lines", List.of(Map.of("description", "Riz", "quantity", 1.23456, "unitPrice", 10)));
        assertThat(handler.handle(op("CREATE_INVOICE", p), ACTOR, TENANT).reason()).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(drafts);
    }

    @Test
    @DisplayName("UPDATE_INVOICE replaces the draft content of the invoice named by invoiceId")
    void update() {
        when(drafts.update(eq(invoiceId), any(), eq(TENANT))).thenReturn(invoice());
        Map<String, Object> p = content();
        p.put("invoiceId", invoiceId.toString());

        assertThat(handler.handle(op("UPDATE_INVOICE", p), ACTOR, TENANT).status())
                .isEqualTo(SyncOperationStatus.APPLIED);
        verify(drafts).update(eq(invoiceId), any(), eq(TENANT));
    }

    @Test
    @DisplayName("ISSUE_INVOICE: numbering by the server's use case")
    void issue() {
        when(lifecycle.issue(invoiceId, ACTOR, TENANT)).thenReturn(invoice());
        assertThat(handler.handle(op("ISSUE_INVOICE", Map.of("invoiceId", invoiceId.toString())), ACTOR, TENANT)
                .status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(lifecycle).issue(invoiceId, ACTOR, TENANT);
    }

    @Test
    @DisplayName("PAY_INVOICE: payment day and method passed; no day → null (today on the server)")
    void pay() {
        when(lifecycle.pay(eq(invoiceId), any(), any(), eq(ACTOR), eq(TENANT))).thenReturn(invoice());

        handler.handle(op("PAY_INVOICE", Map.of("invoiceId", invoiceId.toString(), "paymentMethod", "Espèces",
                "paidAt", "2026-10-30")), ACTOR, TENANT);
        verify(lifecycle).pay(invoiceId, "Espèces", LocalDate.parse("2026-10-30"), ACTOR, TENANT);

        handler.handle(op("PAY_INVOICE", Map.of("invoiceId", invoiceId.toString(), "paymentMethod", "Virement")),
                ACTOR, TENANT);
        verify(lifecycle).pay(eq(invoiceId), eq("Virement"), isNull(), eq(ACTOR), eq(TENANT));
    }

    @Test
    @DisplayName("PAY_INVOICE refused by the domain (date before issue): REJECTED with the domain code")
    void payDateInvalid() {
        when(lifecycle.pay(eq(invoiceId), any(), any(), eq(ACTOR), eq(TENANT)))
                .thenThrow(new DomainException(ErrorCode.INVOICE_PAYMENT_DATE_INVALID, "Invalid payment date"));
        SyncOperationResult r = handler.handle(op("PAY_INVOICE", Map.of("invoiceId", invoiceId.toString(),
                "paidAt", "2026-01-01")), ACTOR, TENANT);
        assertThat(r.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(r.reason()).isEqualTo("INVOICE_PAYMENT_DATE_INVALID");
    }

    @Test
    @DisplayName("PAY_INVOICE with an unreadable date: VALIDATION_ERROR, use case not called")
    void payUnreadableDate() {
        SyncOperationResult r = handler.handle(op("PAY_INVOICE", Map.of("invoiceId", invoiceId.toString(),
                "paidAt", "hier")), ACTOR, TENANT);
        assertThat(r.reason()).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("CANCEL_INVOICE: reason required; owner only (employee → FORBIDDEN, nothing provisioned or written)")
    void cancel() {
        when(lifecycle.cancel(invoiceId, "Erreur de client", ACTOR, TENANT)).thenReturn(invoice());
        assertThat(handler.handle(op("CANCEL_INVOICE", Map.of("invoiceId", invoiceId.toString(),
                "reason", "Erreur de client")), ACTOR, TENANT).status()).isEqualTo(SyncOperationStatus.APPLIED);

        assertThat(handler.handle(op("CANCEL_INVOICE", Map.of("invoiceId", invoiceId.toString())), ACTOR, TENANT)
                .reason()).isEqualTo("VALIDATION_ERROR");

        as("EMPLOYEE", EMPLOYEE);
        SyncOperationResult r = handler.handle(op("CANCEL_INVOICE", Map.of("invoiceId", invoiceId.toString(),
                "reason", "Erreur")), ACTOR, TENANT);
        assertThat(r.reason()).isEqualTo("FORBIDDEN");
        verify(lifecycle, never()).cancel(eq(invoiceId), eq("Erreur"), any(), any());
    }

    @Test
    @DisplayName("employee: create, issue and pay allowed (same permissions as the REST endpoints)")
    void employeeAllowed() {
        as("EMPLOYEE", EMPLOYEE);
        when(lifecycle.issue(invoiceId, ACTOR, TENANT)).thenReturn(invoice());
        assertThat(handler.handle(op("ISSUE_INVOICE", Map.of("invoiceId", invoiceId.toString())), ACTOR, TENANT)
                .status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    @DisplayName("no authenticated role: FORBIDDEN")
    void noRole() {
        SecurityContextHolder.clearContext();
        when(permissions.permissionsOf(anyCollection())).thenReturn(Set.of());
        assertThat(handler.handle(op("ISSUE_INVOICE", Map.of("invoiceId", invoiceId.toString())), ACTOR, TENANT)
                .reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("contract keevo/contracts/invoice-sync-payloads.json: every payload the app queues is read and applied")
    void contractPayloads() throws Exception {
        com.fasterxml.jackson.databind.JsonNode contract = objectMapper.readTree(
                java.nio.file.Path.of("..", "contracts", "invoice-sync-payloads.json").toFile());
        UUID id = UUID.fromString(contract.path("invoiceId").asText());
        Invoice any = invoice();
        when(drafts.create(eq(id), any(), eq(ACTOR), eq(TENANT))).thenReturn(any);
        when(drafts.update(eq(id), any(), eq(TENANT))).thenReturn(any);
        when(lifecycle.issue(id, ACTOR, TENANT)).thenReturn(any);
        when(lifecycle.pay(eq(id), any(), any(), eq(ACTOR), eq(TENANT))).thenReturn(any);
        when(lifecycle.cancel(eq(id), any(), eq(ACTOR), eq(TENANT))).thenReturn(any);
        java.util.Set<String> types = new java.util.HashSet<>();

        for (com.fasterxml.jackson.databind.JsonNode op : contract.path("operations")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(op.path("payload"), Map.class);
            String type = op.path("operationType").asText();
            types.add(type);
            SyncOperationResult r = handler.handle(op(type, payload), ACTOR, TENANT);
            assertThat(r.status()).as("%s: %s", op.path("name").asText(), r.reason())
                    .isEqualTo(SyncOperationStatus.APPLIED);
        }

        assertThat(types).isEqualTo(handler.supportedTypes());
        ArgumentCaptor<ManageDraftInvoiceUseCase.DraftContent> content =
                ArgumentCaptor.forClass(ManageDraftInvoiceUseCase.DraftContent.class);
        verify(drafts).create(eq(id), content.capture(), eq(ACTOR), eq(TENANT));
        assertThat(content.getValue().lines()).extracting(LineInput::treatment)
                .containsExactly(VatTreatment.STANDARD, VatTreatment.EXEMPT);
        assertThat(content.getValue().lines().get(1).quantity()).isEqualByComparingTo("1.5");
        verify(lifecycle).pay(id, "Espèces", null, ACTOR, TENANT);
        verify(lifecycle).pay(id, "Mobile Money", LocalDate.parse("2026-10-25"), ACTOR, TENANT);
        verify(lifecycle).cancel(id, "Erreur de client", ACTOR, TENANT);
    }
}

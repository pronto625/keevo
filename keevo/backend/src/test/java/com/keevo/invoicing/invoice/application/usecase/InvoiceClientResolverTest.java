package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.api.ClientSummary;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 14.3 — invoice client resolution and suggestions through the optional crm capability. */
class InvoiceClientResolverTest {

    private static final String TENANT = "kv_inv143";
    private static final UUID ID = UUID.randomUUID();

    private final CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
    private final ClientLookupPort crm = mock(ClientLookupPort.class);
    private final InvoiceClientResolver resolver = new InvoiceClientResolver(capabilities);

    private void crmOn() {
        when(capabilities.find(ClientLookupPort.class, TENANT)).thenReturn(Optional.of(crm));
    }

    private void crmOff() {
        when(capabilities.find(ClientLookupPort.class, TENANT)).thenReturn(Optional.empty());
    }

    private static void assertNameRequired(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.getDetails()).containsEntry("reason", "Le nom du client est obligatoire");
        });
    }

    @Test
    void noId_freeText_capabilityNotConsulted() {
        ClientInfo typed = new ClientInfo(null, "Awa", "P01", "Akwa", "+237690000001");
        assertThat(resolver.resolve(typed, TENANT)).isEqualTo(typed);
        verifyNoInteractions(capabilities);
    }

    @Test
    void noId_noName_validationError() {
        assertNameRequired(() -> resolver.resolve(new ClientInfo(null, "  ", null, null, null), TENANT));
        assertNameRequired(() -> resolver.resolve(null, TENANT));
    }

    @Test
    void crmOn_knownClient_blankNameAndPhone_prefilledFromCrm_linkKept() {
        crmOn();
        when(crm.find(TENANT, ID)).thenReturn(Optional.of(new ClientSummary(ID, "Mireille", "+237690000011", false)));
        ClientInfo resolved = resolver.resolve(new ClientInfo(ID, "", "P02", "Bonapriso", null), TENANT);
        assertThat(resolved).isEqualTo(new ClientInfo(ID, "Mireille", "P02", "Bonapriso", "+237690000011"));
    }

    @Test
    void crmOn_knownClient_typedValuesWin() {
        crmOn();
        when(crm.find(TENANT, ID)).thenReturn(Optional.of(new ClientSummary(ID, "Mireille", "+237690000011", true)));
        ClientInfo resolved = resolver.resolve(new ClientInfo(ID, "SARL X", null, null, "+237677000000"), TENANT);
        assertThat(resolved).isEqualTo(new ClientInfo(ID, "SARL X", null, null, "+237677000000"));
    }

    @Test
    void crmOn_unknownClient_clientNotFound() {
        crmOn();
        when(crm.find(TENANT, ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(new ClientInfo(ID, "Paul", null, null, null), TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLIENT_NOT_FOUND));
    }

    @Test
    void crmOff_idDropped_typedTextKept_noNameStillRequired() {
        crmOff();
        assertThat(resolver.resolve(new ClientInfo(ID, "Paul", null, null, "+237690000001"), TENANT))
                .isEqualTo(new ClientInfo(null, "Paul", null, null, "+237690000001"));
        assertNameRequired(() -> resolver.resolve(new ClientInfo(ID, null, null, null, null), TENANT));
    }

    @Test
    void crmOff_update_storedLinkKept_newOrDifferentIdDropped() {
        crmOff();
        assertThat(resolver.resolve(new ClientInfo(ID, "Paul", null, null, null), TENANT, ID))
                .isEqualTo(new ClientInfo(ID, "Paul", null, null, null));
        assertThat(resolver.resolve(new ClientInfo(ID, "Paul", null, null, null), TENANT, UUID.randomUUID()).clientId())
                .isNull();
        assertThat(resolver.resolve(new ClientInfo(ID, "Paul", null, null, null), TENANT, null).clientId()).isNull();
    }

    @Test
    void providerExceptionsPropagate() {
        crmOn();
        when(crm.find(TENANT, ID)).thenThrow(new IllegalStateException("tenant mismatch"));
        assertThatThrownBy(() -> resolver.resolve(new ClientInfo(ID, "Paul", null, null, null), TENANT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suggest_crmOn_mapsTheSearch() {
        crmOn();
        UUID other = UUID.randomUUID();
        when(crm.search(TENANT, "mir", 20)).thenReturn(List.of(new ClientSummary(other, "Amir", "+237690000012", false),
                new ClientSummary(ID, "Mireille", "+237690000011", false)));
        var suggestions = resolver.suggest("mir", 20, TENANT);
        assertThat(suggestions.crmAvailable()).isTrue();
        assertThat(suggestions.clients()).containsExactly(
                new InvoiceClientResolver.ClientSuggestion(other, "Amir", "+237690000012"),
                new InvoiceClientResolver.ClientSuggestion(ID, "Mireille", "+237690000011"));
    }

    @Test
    void suggest_crmOff_unavailableAndEmpty() {
        crmOff();
        var suggestions = resolver.suggest("mir", 20, TENANT);
        assertThat(suggestions.crmAvailable()).isFalse();
        assertThat(suggestions.clients()).isEmpty();
    }
}

package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.CreateSupplierInput;
import com.keevo.crm.api.SupplierView;
import com.keevo.crm.api.UpdateSupplierInput;
import com.keevo.crm.contact.application.usecase.ArchiveSupplierUseCase;
import com.keevo.crm.contact.application.usecase.GetSupplierByProductUseCase;
import com.keevo.crm.contact.application.usecase.GetSupplierProfileUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveSupplierUseCase;
import com.keevo.crm.contact.application.usecase.UpdateSupplierUseCase;
import com.keevo.crm.contact.domain.entity.Supplier;
import com.keevo.crm.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2a — the crm supplier ports delegate to the repository / use cases and map faithfully. */
@ExtendWith(MockitoExtension.class)
class SupplierApiAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private SupplierRepository supplierRepository;
    @Mock private GetSupplierByProductUseCase getSupplierByProduct;
    @Mock private GetSupplierProfileUseCase getSupplierProfile;
    @Mock private UpdateSupplierUseCase updateSupplier;
    @Mock private ArchiveSupplierUseCase archiveSupplier;
    @Mock private UnarchiveSupplierUseCase unarchiveSupplier;

    private SupplierApiAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TENANT = "kv_test";
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new SupplierApiAdapter(supplierRepository, getSupplierByProduct, getSupplierProfile,
                updateSupplier, archiveSupplier, unarchiveSupplier);
    }

    private static Supplier supplier() {
        return new Supplier(ID, "Distributeur SA", "+237690000099", "d@s.cm", false, T0, T0);
    }

    @Test
    void existsById_reflectsTheRepository() {
        when(supplierRepository.findById(ID)).thenReturn(Optional.of(supplier()));

        UUID unknown = UUID.randomUUID();
        when(supplierRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(adapter.existsById(TenantContext.getCurrentTenant(), ID)).isTrue();
        assertThat(adapter.existsById(TenantContext.getCurrentTenant(), unknown)).isFalse();
    }

    @Test
    void findByProductId_returnsTheSupplierWithAllItsProductIds() {
        UUID other = UUID.randomUUID();
        when(getSupplierByProduct.execute(PRODUCT)).thenReturn(Optional.of(supplier()));
        when(getSupplierProfile.execute(ID))
                .thenReturn(new GetSupplierProfileUseCase.SupplierProfileResult(supplier(), List.of(PRODUCT, other)));

        assertThat(adapter.findByProductId(TenantContext.getCurrentTenant(), PRODUCT)).contains(new SupplierView(ID, "Distributeur SA",
                "+237690000099", "d@s.cm", false, List.of(PRODUCT, other), T0, T0));
    }

    @Test
    void findByProductId_noLinkedSupplier_isEmpty_withoutProfileLookup() {
        when(getSupplierByProduct.execute(PRODUCT)).thenReturn(Optional.empty());

        assertThat(adapter.findByProductId(TenantContext.getCurrentTenant(), PRODUCT)).isEmpty();
        verifyNoInteractions(getSupplierProfile);
    }

    @Test
    void createWithId_savesThenLinksTheProducts() {
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierView view = adapter.createWithId(TenantContext.getCurrentTenant(), 
                new CreateSupplierInput(ID, "Distributeur SA", "+237690000099", null, List.of(PRODUCT)));

        InOrder order = inOrder(supplierRepository);
        order.verify(supplierRepository).save(argThat(s -> ID.equals(s.id()) && !s.archived()));
        order.verify(supplierRepository).linkProducts(ID, List.of(PRODUCT));
        assertThat(view.productIds()).containsExactly(PRODUCT);
    }

    @Test
    void createWithId_withoutProducts_linksNothing() {
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adapter.createWithId(TenantContext.getCurrentTenant(), new CreateSupplierInput(ID, "Distributeur SA", "+237690000099", null, null));
        adapter.createWithId(TenantContext.getCurrentTenant(), new CreateSupplierInput(ID, "Distributeur SA", "+237690000099", null, List.of()));

        verify(supplierRepository, never()).linkProducts(any(), any());
    }

    @Test
    void createWithId_invalidPhone_isRejected_andNothingIsSaved() {
        assertThatThrownBy(() -> adapter.createWithId(TenantContext.getCurrentTenant(), new CreateSupplierInput(ID, "X", "notE164", null, null)))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(supplierRepository);
    }

    @Test
    void update_mapsOntoTheUseCaseCommand() {
        when(updateSupplier.execute(any())).thenReturn(supplier());
        List<UUID> links = List.of(PRODUCT);

        SupplierView view = adapter.update(TenantContext.getCurrentTenant(), new UpdateSupplierInput(ID, "Distributeur SA", "+237690000099", null, links, ACTOR));

        verify(updateSupplier).execute(new UpdateSupplierUseCase.UpdateSupplierCommand(
                ID, "Distributeur SA", "+237690000099", null, links, ACTOR));
        assertThat(view.productIds()).containsExactly(PRODUCT);
        verifyNoInteractions(getSupplierProfile);
    }

    @Test
    void update_linksKept_reportsTheRealLinkedProducts() {
        UUID other = UUID.randomUUID();
        when(updateSupplier.execute(any())).thenReturn(supplier());
        when(getSupplierProfile.execute(ID))
                .thenReturn(new GetSupplierProfileUseCase.SupplierProfileResult(supplier(), List.of(PRODUCT, other)));

        SupplierView view = adapter.update(TenantContext.getCurrentTenant(), new UpdateSupplierInput(ID, "Distributeur SA", "+237690000099", null, null, ACTOR));

        assertThat(view.productIds()).containsExactly(PRODUCT, other);
    }

    @Test
    void contactViews_nullProductIds_stayNull() {
        assertThat(ContactViews.of(supplier(), null).productIds()).isNull();
    }

    @Test
    void archive_and_unarchive_delegate() {
        adapter.archive(TENANT, ID, ACTOR);
        adapter.unarchive(TENANT, ID, ACTOR);

        verify(archiveSupplier).execute(ID, ACTOR, TENANT);
        verify(unarchiveSupplier).execute(ID, ACTOR, TENANT);
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.existsById("kv_other1", ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.existsById(null, ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.archive("kv_other1", ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.archive(null, ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(supplierRepository, archiveSupplier);
    }
}

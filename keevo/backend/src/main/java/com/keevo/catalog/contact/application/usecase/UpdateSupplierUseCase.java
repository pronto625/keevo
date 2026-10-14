package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.event.SupplierProductsChangedEvent;
import com.keevo.catalog.contact.domain.event.SupplierUpdatedEvent;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * UpdateSupplierUseCase — updates mutable fields on an existing supplier (Story 2.5).
 *
 * <p>Records in the action history a change of name / phone / e-mail (SUPPLIER_UPDATED)
 * and a change of the products supplied (SUPPLIER_PRODUCTS_UPDATED). A no-op update
 * records nothing.
 */
@Service
public class UpdateSupplierUseCase {

    private final SupplierRepository supplierRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateSupplierUseCase(SupplierRepository supplierRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.supplierRepository = supplierRepository;
        this.eventPublisher = eventPublisher;
    }

    public record UpdateSupplierCommand(
            UUID supplierId,
            String name,
            String phone,
            String email,
            List<UUID> productIds,
            UUID actorId
    ) {}

    public Supplier execute(UpdateSupplierCommand cmd) {
        Supplier existing = supplierRepository.findById(cmd.supplierId())
                .orElseThrow(() -> new DomainException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "supplierId=" + cmd.supplierId()));

        Supplier updated = new Supplier(
                existing.id(),
                cmd.name()  != null ? cmd.name()  : existing.name(),
                cmd.phone() != null ? cmd.phone() : existing.phone(),
                cmd.email() != null ? cmd.email() : existing.email(),
                existing.archived(),
                existing.createdAt(),
                Instant.now()
        );
        Supplier saved = supplierRepository.save(updated);
        String tenantId = TenantContext.getCurrentTenant();

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff("name", existing.name(), saved.name(), before, after);
        diff("phone", existing.phone(), saved.phone(), before, after);
        diff("email", existing.email(), saved.email(), before, after);
        if (!after.isEmpty()) {
            eventPublisher.publishEvent(new SupplierUpdatedEvent(
                    saved.id(), saved.name(), before, after, cmd.actorId(), tenantId, Instant.now()));
        }

        if (cmd.productIds() != null) {
            Set<UUID> previous = new HashSet<>(supplierRepository.findProductIdsBySupplier(existing.id()));
            Set<UUID> next = new HashSet<>(cmd.productIds());
            supplierRepository.linkProducts(existing.id(), cmd.productIds());
            int added = (int) next.stream().filter(id -> !previous.contains(id)).count();
            int removed = (int) previous.stream().filter(id -> !next.contains(id)).count();
            if (added > 0 || removed > 0) {
                eventPublisher.publishEvent(new SupplierProductsChangedEvent(
                        saved.id(), saved.name(), previous.size(), next.size(), added, removed,
                        cmd.actorId(), tenantId, Instant.now()));
            }
        }
        return saved;
    }

    private static void diff(String key, String oldValue, String newValue,
                             Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(blankToNull(oldValue), blankToNull(newValue))) {
            before.put(key, oldValue);
            after.put(key, newValue);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

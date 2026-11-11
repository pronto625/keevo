package com.keevo.identity.role.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.access.AccessStateChangePublisher;
import com.keevo.shared.module.application.ModulePermissions;
import com.keevo.shared.module.application.PermissionCatalog;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 19.14 — the owner defines the roles of his company (Command services over {@link CustomRoleStore}): a name, a
 * base role (« Gérant », « Employé » or « Lecteur » — the baseline behaviour it keeps, immutable), the permissions he
 * ticks in the {@link PermissionCatalog} of his usable modules, and optionally the stores it applies to.
 *
 * <p>Writes are serialized per company ({@link TransactionLockPort}, key {@code roles:<schema>}) so the name
 * uniqueness and the role cap hold under concurrent requests (the unique index is the last barrier). Every change is
 * audited with French values (never a permission code). The access-state cache of the company's members is evicted
 * by the {@code tenant_roles} trigger (V78) on commit.
 */
@Service
public class CustomRoleService {

    /** Maximum number of custom roles per company. */
    static final int MAX_ROLES = 50;

    /** Maximum length of a role name. */
    static final int NAME_MAX = 60;

    static final String NAME_INVALID = "Le nom du rôle doit faire entre 1 et 60 caractères.";
    static final String BASE_ROLE_INVALID = "Choisissez le type de base du rôle : Gérant, Employé ou Lecteur.";
    static final String BASE_ROLE_IMMUTABLE =
            "Le type de base d'un rôle ne peut pas être modifié : créez un nouveau rôle.";
    static final String PERMISSION_UNAVAILABLE = "Un des droits choisis n'est pas disponible pour votre entreprise.";
    static final String VIEWER_WRITE = "Un rôle de type Lecteur ne peut recevoir que des droits de consultation.";
    static final String STORE_UNKNOWN = "Une des boutiques choisies est introuvable.";
    static final String STORES_EMPTY =
            "Choisissez au moins une boutique, ou laissez le rôle valable dans toutes les boutiques.";
    static final String TOO_MANY = "Votre entreprise a atteint le nombre maximal de rôles (" + MAX_ROLES + ").";
    static final String PERMISSIONS_REQUIRED =
            "Indiquez les droits du rôle (la liste complète, même vide), et ses boutiques s'il en a.";

    /** French labels of the base roles (the « type de base » of a custom role). */
    public static final Map<String, String> BASE_ROLE_LABELS = Map.of(
            Role.MANAGER.name(), "Gérant",
            Role.EMPLOYEE.name(), "Employé",
            Role.VIEWER.name(), "Lecteur");

    /**
     * What the owner sends to create or update a role.
     *
     * @param storeIds {@code null} = every store; otherwise a non-empty list of the company's stores
     */
    public record RoleInput(String name, String baseRole, Collection<String> permissions, Collection<UUID> storeIds) {
    }

    /** A built-in role shown as a preset (« Gérant », « Employé », « Lecteur ») with its default permissions. */
    public record BuiltIn(String baseRole, String name, Set<String> permissions) {
    }

    private final CustomRoleStore store;
    private final TenantRepository tenants;
    private final PermissionCatalog catalog;
    private final ModulePermissions modulePermissions;
    private final StoreQueryPort stores;
    private final TransactionLockPort locks;
    private final AuditPort audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccessStateChangePublisher accessStates;

    public CustomRoleService(CustomRoleStore store, TenantRepository tenants, PermissionCatalog catalog,
                             ModulePermissions modulePermissions, StoreQueryPort stores, TransactionLockPort locks,
                             AuditPort audit, ObjectMapper json, Clock clock, AccessStateChangePublisher accessStates) {
        this.accessStates = accessStates;
        this.store = store;
        this.tenants = tenants;
        this.catalog = catalog;
        this.modulePermissions = modulePermissions;
        this.stores = stores;
        this.locks = locks;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** The company's custom roles with their member counts (one query). */
    @Transactional(readOnly = true)
    public List<CustomRole.WithMembers> list(String tenantSchema) {
        return store.findAll(tenantId(tenantSchema));
    }

    /** The built-in roles as presets, with the permissions they hold by default (manifests' {@code rolePermissions}). */
    public List<BuiltIn> builtIns() {
        List<BuiltIn> presets = new ArrayList<>();
        for (Role role : List.of(Role.MANAGER, Role.EMPLOYEE, Role.VIEWER)) {
            Set<String> granted = modulePermissions.permissionsFor(role).stream()
                    .filter(catalog::isGrantable).collect(Collectors.toCollection(java.util.TreeSet::new));
            presets.add(new BuiltIn(role.name(), BASE_ROLE_LABELS.get(role.name()), granted));
        }
        return presets;
    }

    @Transactional
    public CustomRole create(String tenantSchema, UUID actorId, RoleInput in) {
        UUID tenantId = tenantId(tenantSchema);
        locks.lock("roles:" + tenantSchema);
        String baseRole = baseRole(in.baseRole());
        String name = name(in.name());
        Set<String> permissions = permissions(tenantSchema, baseRole, in.permissions(), Set.of());
        List<UUID> storeIds = storeIds(tenantSchema, in.storeIds());
        if (store.count(tenantId) >= MAX_ROLES) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, TOO_MANY);
        }
        if (store.nameTaken(tenantId, name, null)) {
            throw new DomainException(ErrorCode.ROLE_NAME_TAKEN);
        }
        Instant now = clock.instant();
        CustomRole role = new CustomRole(UUID.randomUUID(), tenantId, name, baseRole, permissions, storeIds, now, now);
        store.insert(role, actorId);
        record(actorId, tenantSchema, "ROLE_CREATED", role, null, snapshot(tenantSchema, role));
        return role;
    }

    /** Updates name, permissions and stores; returns the role with its member count. */
    @Transactional
    public CustomRole.WithMembers update(String tenantSchema, UUID actorId, UUID roleId, RoleInput in) {
        UUID tenantId = tenantId(tenantSchema);
        locks.lock("roles:" + tenantSchema);
        CustomRole current = store.findById(tenantId, roleId)
                .orElseThrow(() -> new DomainException(ErrorCode.ROLE_NOT_FOUND));
        if (in.baseRole() != null && !in.baseRole().isBlank() && !current.baseRole().equals(in.baseRole().trim())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, BASE_ROLE_IMMUTABLE);
        }
        String name = name(in.name());
        if (in.permissions() == null) { // PUT replaces the role: a missing list never silently removes every right
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, PERMISSIONS_REQUIRED);
        }
        Set<String> permissions = permissions(tenantSchema, current.baseRole(), in.permissions(),
                current.permissions());
        List<UUID> storeIds = storeIds(tenantSchema, in.storeIds());
        if (store.nameTaken(tenantId, name, roleId)) {
            throw new DomainException(ErrorCode.ROLE_NAME_TAKEN);
        }
        CustomRole updated = new CustomRole(current.id(), tenantId, name, current.baseRole(), permissions, storeIds,
                current.createdAt(), clock.instant());
        store.update(updated);
        accessStates.tenantChanged(tenantSchema); // members' cached rights (the V78 trigger also broadcasts it)
        record(actorId, tenantSchema, "ROLE_UPDATED", updated, snapshot(tenantSchema, current),
                snapshot(tenantSchema, updated));
        return new CustomRole.WithMembers(updated, store.memberCount(tenantId, roleId));
    }

    @Transactional
    public void delete(String tenantSchema, UUID actorId, UUID roleId) {
        UUID tenantId = tenantId(tenantSchema);
        locks.lock("roles:" + tenantSchema);
        CustomRole current = store.findById(tenantId, roleId)
                .orElseThrow(() -> new DomainException(ErrorCode.ROLE_NOT_FOUND));
        if (store.memberCount(tenantId, roleId) > 0) {
            throw new DomainException(ErrorCode.ROLE_IN_USE);
        }
        store.delete(tenantId, roleId);
        accessStates.tenantChanged(tenantSchema);
        record(actorId, tenantSchema, "ROLE_DELETED", current, snapshot(tenantSchema, current), null);
    }

    // ── validation ──────────────────────────────────────────────────────────

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, NAME_INVALID);
        }
        return name;
    }

    private static String baseRole(String raw) {
        String role = raw == null ? null : raw.strip();
        if (role == null || !BASE_ROLE_LABELS.containsKey(role)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, BASE_ROLE_INVALID);
        }
        return role;
    }

    /**
     * Grantable permissions of the company's usable modules (read once); a permission the role already holds stays
     * accepted when its module was disabled since (the owner can still rename or edit the role); a « Lecteur » role
     * only read ones.
     */
    private Set<String> permissions(String tenantSchema, String baseRole, Collection<String> requested,
                                    Set<String> held) {
        Set<String> chosen = new LinkedHashSet<>();
        if (requested != null) {
            for (String code : requested) {
                if (code == null) {
                    throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, PERMISSION_UNAVAILABLE);
                }
                chosen.add(code.strip());
            }
        }
        Set<String> enabled = chosen.isEmpty() ? Set.of() : catalog.enabledModules(tenantSchema);
        for (String code : chosen) {
            boolean grantable = catalog.isGrantable(enabled, code) || (held.contains(code) && catalog.isGrantable(code));
            if (!grantable) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, PERMISSION_UNAVAILABLE);
            }
        }
        if (Role.VIEWER.name().equals(baseRole)
                && chosen.stream().anyMatch(code -> !code.endsWith(ModulePermissions.READ_SUFFIX))) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, VIEWER_WRITE);
        }
        return chosen;
    }

    /** {@code null} = every store; else the distinct ids, each a store of the company (one query). */
    private List<UUID> storeIds(String tenantSchema, Collection<UUID> requested) {
        if (requested == null) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>(requested);
        ids.remove(null);
        if (ids.isEmpty()) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, STORES_EMPTY);
        }
        if (!stores.namesByIds(tenantSchema, ids).keySet().containsAll(ids)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, STORE_UNKNOWN);
        }
        return List.copyOf(ids);
    }

    private UUID tenantId(String tenantSchema) {
        return tenants.findBySchemaName(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND))
                .getId();
    }

    // ── audit ───────────────────────────────────────────────────────────────

    /** French snapshot of a role: name, base role label, permission labels, store names (one query). */
    private String snapshot(String tenantSchema, CustomRole role) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", role.name());
        values.put("baseRole", BASE_ROLE_LABELS.get(role.baseRole()));
        values.put("permissions", role.permissions().stream().map(PermissionCatalog::label)
                .filter(java.util.Objects::nonNull).sorted().collect(Collectors.joining(", ")));
        values.put("stores", role.storeIds() == null ? "Toutes les boutiques"
                : stores.namesByIds(tenantSchema, role.storeIds()).values().stream().sorted()
                        .collect(Collectors.joining(", ")));
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void record(UUID actorId, String tenantSchema, String action, CustomRole role, String before,
                        String after) {
        audit.record(new AuditPort.AuditEntry(actorId, tenantSchema, action, "Role", role.id(), before, after,
                null, role.name(), null, "ONLINE", null));
    }
}

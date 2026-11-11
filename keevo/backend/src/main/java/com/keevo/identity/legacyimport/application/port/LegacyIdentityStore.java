package com.keevo.identity.legacyimport.application.port;

import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.Role;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — persistence of the imported identities ({@code public.users}, {@code public.user_tenant_memberships},
 * {@code "<schema>".employees}, {@code "<schema>".tenant_preferences}, the suspension trace on {@code public.tenants}).
 * Every insert is {@code ON CONFLICT DO NOTHING}; the batches run as one JDBC batch each.
 */
public interface LegacyIdentityStore {

    /** One query (UNION ALL of index lookups). */
    Map<UUID, Set<ConflictKind>> findConflicts(Collection<LegacyAccount> accounts);

    /** @return the ids inserted (absent ids only) */
    Set<UUID> insertAccounts(List<LegacyAccount> accounts, List<Role> roles, List<Boolean> active);

    /** @return account id → membership id, for the pairs inserted */
    Map<UUID, UUID> insertMemberships(UUID tenantId, List<LegacyAccount> accounts, List<Role> roles,
                                      List<Boolean> active);

    /** @return the employee ids inserted (= account ids) */
    Set<UUID> insertEmployees(String tenantSchema, List<ImportedMember> members);

    /** @return whether the row was created */
    boolean insertPreferencesIfAbsent(String tenantSchema, String sectorType);

    /** Sets the suspension date and reason of a suspended tenant when they are not set yet. */
    void traceSuspension(UUID tenantId, String reason);
}

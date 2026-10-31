/// Client contract for a REJECTED sync operation (v1s-16-12).
///
/// The server answers every queued offline operation with APPLIED, DUPLICATE,
/// CONFLICT or REJECTED. A REJECTED result carries a `reason` (the backend
/// `ErrorCode` name, or free text for an unmapped exception). The client
/// classifies it here and `RestSyncService` reconciles the local state:
///
/// * **Transient** reasons ([isTransientSyncRejection]): a concurrent write,
///   a server-side hiccup or an auth/tenant state expected to clear. The
///   operation stays queued, `lastError` is recorded (visible in the queue
///   screen) and the user is told once per distinct reason — some of them
///   (UNAUTHORIZED, DATA_INTEGRITY_VIOLATION…) may never clear.
/// * **Held** reasons ([isHeldSyncRejection], Story 12.7): `MODULE_INACTIVE`,
///   the operation's module is switched off for the account, and (Story 13.5)
///   `MODULE_READ_ONLY`, its subscription expired and it is in its read-only
///   grace — states the owner can undo (renewal), and the data is kept for
///   life (spec-socle §2.2). The
///   operation stays queued with `lastError`, nothing is rolled back, reverted,
///   stripped, dropped or quarantined; it is not counted as pending (pulls
///   keep running) but still protects its entity from pull overwrites. Held
///   operations are pushed again on the first push after the app starts and
///   then at most every [heldRetryInterval], so they apply as soon as the
///   module is active again. A queued operation refused (for any reason)
///   while it references an entity of a held operation is held too.
/// * **Terminal** reasons: everything else. The same payload would be refused
///   again on every push (the server re-evaluates a REJECTED operation each
///   time it is pushed, and the outcome does not change). Per operation:
///   - money / stock-ledger operations ([moneyLedgerOperations]) are never
///     deleted by their own rejection: they go into **quarantine**
///     ([quarantinesOnRejection], v1s-16-15) — `sync_queue.quarantined_at`
///     is set, the operation is kept on the device but never pushed, not
///     counted as pending (so it no longer blocks the server refreshes) and
///     surfaced once ("Bloqué … — action requise"). A quarantined
///     `CREATE_SALE` takes its queued `VALIDATE_SALE` / `CANCEL_SALE` (same
///     `entityId`, [saleChainOperations]) along: the "chain". Only the user
///     ends a quarantine (Paramètres › Synchronisation › File, or
///     `/sync/blocked`): *Renvoyer* (release and push again), *Rattacher*
///     (OWNER, `CREATE_SALE`: re-point lines/store to local entities) or
///     *Abandonner* (OWNER, only after a successful export; logged in the
///     local sync history). "Zero sales lost": the server also keeps the
///     refused payload in `sync_error_log` for 30 days;
///   - creations of a catalog/contact/store entity ([ghostCreateOperations])
///     refused for good (e.g. `PLAN_LIMIT_EXCEEDED`: the user is told to
///     upgrade; upgrading later does not resurrect it — the user recreates
///     it) roll the ghost local row back and drop its queued follow-ups —
///     including the stock-ledger operations (RECORD_STOCK_ENTRY,
///     STOCK_ADJUST, STOCK_TRANSFER) of a rolled-back product or store, which
///     could never apply. Sales and day closures are never dropped: a queued
///     sale referencing a rolled-back client loses its `clientId`, and a
///     queued product write referencing a rolled-back category loses its
///     `categoryId`, instead of being refused in turn;
///   - an inventory count refused for good is removed locally so a later
///     offline validation does not apply it;
///   - inventory sessions refused by the server are abandoned locally and
///     their offline stock adjustments are reverted;
///   - any other operation is dropped after one notice, so that
///     `hasPendingOperations()` no longer blocks the server refresh that
///     corrects the local state.
library;

/// Reasons the client retries silently: the operation stays queued.
const transientSyncRejectionReasons = {
  'OPTIMISTIC_LOCK',
  'INTERNAL_ERROR',
  'TRANSACTION_ROLLED_BACK',
  'DATA_INTEGRITY_VIOLATION',
  'UNAUTHORIZED',
  'SUBSCRIPTION_NOT_FOUND',
  'TENANT_NOT_FOUND',
};

/// Reason of a held operation (Story 12.7): the module is off for the account.
const heldSyncRejectionReason = 'MODULE_INACTIVE';

/// Reason of a held operation (Story 13.5): the module's subscription expired
/// and it is read-only during its grace period (sync operations are writes).
const readOnlySyncRejectionReason = 'MODULE_READ_ONLY';

/// Every reason that holds an operation (Stories 12.7, 13.5).
const heldSyncRejectionReasons = [
  heldSyncRejectionReason,
  readOnlySyncRejectionReason,
];

/// The reason recorded on an operation held because it references entities of
/// held operations with [reasons]: `MODULE_INACTIVE` wins over
/// `MODULE_READ_ONLY` (the stronger state), so the result does not depend on
/// the order the held operations are read in. Null when [reasons] holds none.
String? dominantHeldReason(Iterable<String?> reasons) {
  final held = reasons.where(isHeldSyncRejection).toSet();
  if (held.isEmpty) return null;
  return held.contains(heldSyncRejectionReason)
      ? heldSyncRejectionReason
      : readOnlySyncRejectionReason;
}

/// Whether a REJECTED [reason] holds the operation (kept, nothing undone,
/// retried at [heldRetryInterval]).
bool isHeldSyncRejection(String? reason) =>
    reason != null && heldSyncRejectionReasons.contains(reason);

/// Minimum delay between two pushes of a held operation (besides the first
/// push after the app starts). A module is re-activated by the owner or the
/// platform, rarely more than a few times a day: 6 hours keeps held operations
/// from adding a refused round-trip to every 5-minute sync cycle.
const heldRetryInterval = Duration(hours: 6);

/// Whether a REJECTED [reason] is expected to clear on a later push. A null
/// reason (no information from the server) is treated as transient.
bool isTransientSyncRejection(String? reason) =>
    reason == null || transientSyncRejectionReasons.contains(reason);

/// Whether a REJECTED [operation] goes into quarantine (v1s-16-15): a money /
/// stock-ledger operation refused for good. `DATA_INTEGRITY_VIOLATION` is
/// transient for other operations, but for a money operation it is a DB
/// constraint violated by the same payload on every push: retrying it
/// forever would pin the queue.
bool quarantinesOnRejection(String operation, String? reason) =>
    moneyLedgerOperations.contains(operation) &&
    !isHeldSyncRejection(reason) &&
    (!isTransientSyncRejection(reason) ||
        reason == 'DATA_INTEGRITY_VIOLATION');

/// Operations quarantined together with the `CREATE_SALE` of the same
/// `entityId` (the sale "chain"): they cannot apply without the sale.
const saleChainOperations = {'VALIDATE_SALE', 'CANCEL_SALE'};

/// Money and stock-ledger operations: never removed from the queue by their
/// own rejection (quarantined when refused for good). Two exceptions: `CANCEL_SALE` rejected with
/// `SALE_ALREADY_CANCELLED` (settled), and the stock operations of a product
/// or store whose offline creation was rolled back. Sales are never dropped.
const moneyLedgerOperations = {
  'CREATE_SALE',
  'VALIDATE_SALE',
  'CANCEL_SALE',
  'CREATE_DAY_CLOSURE',
  'STOCK_ADJUST',
  'RECORD_STOCK_ENTRY',
  'STOCK_TRANSFER',
};

/// Creations whose local row is a ghost once the server refuses them.
const ghostCreateOperations = {
  'CREATE_STORE',
  'CREATE_PRODUCT',
  'CREATE_DRAFT_PRODUCT',
  'CREATE_CATEGORY',
  'CREATE_CLIENT',
  'CREATE_SUPPLIER',
};

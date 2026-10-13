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
/// * **Terminal** reasons: everything else. The same payload will be refused
///   again on every push (the server even replays the settled REJECTED
///   result). Per operation:
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

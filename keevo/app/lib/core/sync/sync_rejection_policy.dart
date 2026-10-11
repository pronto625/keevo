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
///     deleted by their own rejection: they stay queued and flagged, surfaced
///     once per reason ("zero sales lost"; the payload is also in the server
///     `sync_error_log`);
///   - creations of a catalog/contact/store entity ([ghostCreateOperations])
///     roll the ghost local row back and drop its queued follow-ups —
///     including the stock-ledger operations (RECORD_STOCK_ENTRY,
///     STOCK_ADJUST, STOCK_TRANSFER) of a rolled-back product or store, which
///     could never apply. Sales and day closures are never dropped: a queued
///     sale referencing a rolled-back client loses its `clientId`, and a
///     queued product write referencing a rolled-back category loses its
///     `categoryId`, instead of being refused in turn;
///   - an inventory count refused for good is removed locally so a later
///     offline validation does not apply it;
///     `PLAN_LIMIT_EXCEEDED` is terminal: the product/store is removed from
///     the device and the user is told to upgrade (upgrading later does not
///     resurrect it — the user recreates it);
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

/// Money and stock-ledger operations: never removed from the queue by their
/// own rejection. Two exceptions: `CANCEL_SALE` rejected with
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

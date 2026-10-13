import 'package:uuid/uuid.dart';

/// Deterministic (RFC 4122 v5) stock-movement ids — v1s-13-13.
///
/// Mirror of the backend `com.keevo.shared.domain.DeterministicIds`: the
/// device's local SALE_CANCELLED / ADJUSTMENT movement and the one the server
/// writes for the same owner cancel/correct get the same id, so the pull's
/// INSERT OR IGNORE keeps a single history row. Namespace and names must stay
/// byte-identical to the Java side (lower-case canonical UUID strings).
const String stockMovementNamespace = '4b6f1d2e-8c3a-4e5f-9a7b-1c2d3e4f5a6b';

/// SALE_CANCELLED movement of a sale item: v5(ns, `SALE_CANCELLED:<itemId>`).
String saleCancelledMovementId(String saleItemId) => const Uuid()
    .v5(stockMovementNamespace, 'SALE_CANCELLED:${saleItemId.toLowerCase()}');

/// ADJUSTMENT movement of a sale item for one correction request:
/// v5(ns, `ADJUSTMENT:<correctionId>:<itemId>`).
String saleCorrectionMovementId(String correctionId, String saleItemId) =>
    const Uuid().v5(stockMovementNamespace,
        'ADJUSTMENT:${correctionId.toLowerCase()}:${saleItemId.toLowerCase()}');

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

// Module code is loaded on first use only (Story 15.3, lazy loading).
import '../../features/invoicing/presentation/open_sale_invoice.dart'
    deferred as invoicing_actions;

/// Story 15.3 — runs one extension contribution. [args] is the slot's
/// context (e.g. `{'saleId': …}` on the checkout slot).
typedef ExtensionActionHandler = Future<void> Function(
    BuildContext context, WidgetRef ref, Map<String, Object?> args);

/// « Facturer » (Invoicing, `pos.checkout.post-sale-actions`): opens the
/// sale's automatic invoice. Args: `saleId`.
const kOpenSaleInvoiceAction = 'invoicing.open-sale-invoice';

/// Action key (sent by the server in each contribution) → handler.
///
/// The label, order and permission of a contribution come from the server
/// only; this registry holds what the app does when the button is tapped. A
/// key missing here (backend newer than the app) is not rendered. Handlers
/// of heavy modules load their library with `deferred as`.
final Map<String, ExtensionActionHandler> kExtensionActions = {
  kOpenSaleInvoiceAction: (context, ref, args) async {
    await invoicing_actions.loadLibrary();
    if (!context.mounted) return;
    await invoicing_actions.openSaleInvoice(context, args);
  },
};

/// The action registry (overridden in tests).
final extensionActionsProvider =
    Provider<Map<String, ExtensionActionHandler>>((ref) => kExtensionActions);

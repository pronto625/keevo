import 'package:go_router/go_router.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_core/widget/deferred_widget.dart';

// Module code is loaded on first use only (Story 15.3, lazy loading): this
// descriptor is the only library of the package the app imports eagerly.
import 'presentation/open_sale_invoice.dart' deferred as invoicing_actions;
import 'presentation/page/invoice_detail_page.dart' deferred as invoice_detail;

/// Module id of the backend manifest (`keevo/modules/invoicing/module.json`).
const kInvoicingModuleId = 'invoicing';

/// « Facturer » (`pos.checkout.post-sale-actions`): opens the sale's
/// automatic invoice. Args: `saleId`. Label/order/permission are the
/// server's; this is only the key of the handler.
const kOpenSaleInvoiceAction = 'invoicing.open-sale-invoice';

/// Story 15.4c — the Invoicing module's UI, registered by the app shell
/// (`lib/core/modules/registered_modules.dart`): the invoice screen
/// `/invoicing/:id` (manifest route, 15.2 guard) and the « Facturer » handler.
final invoicingModuleUi = KeevoModuleUi(
  id: kInvoicingModuleId,
  routes: [
    // Minimal read-only invoice screen (15.3); full UI in 15.5.
    GoRoute(
      path: '/invoicing/:id',
      builder: (_, state) {
        final id = state.pathParameters['id']!;
        return DeferredWidget(
          loader: invoice_detail.loadLibrary,
          builder: (_) => invoice_detail.InvoiceDetailPage(invoiceId: id),
        );
      },
    ),
  ],
  actions: {
    kOpenSaleInvoiceAction: (context, ref, args) async {
      await invoicing_actions.loadLibrary();
      if (!context.mounted) return;
      await invoicing_actions.openSaleInvoice(context, args);
    },
  },
);

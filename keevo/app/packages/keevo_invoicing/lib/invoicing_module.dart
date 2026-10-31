import 'package:go_router/go_router.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_core/widget/deferred_widget.dart';

// Module code is loaded on first use only (Story 15.3, lazy loading): this
// descriptor is the only library of the package the app imports eagerly.
import 'presentation/open_sale_invoice.dart' deferred as invoicing_actions;
import 'presentation/page/invoice_detail_page.dart' deferred as invoice_detail;
import 'presentation/page/invoice_form_page.dart' deferred as invoice_form;
import 'presentation/page/invoice_list_page.dart' deferred as invoice_list;
import 'presentation/page/invoicing_settings_page.dart'
    deferred as invoicing_settings;

/// Module id of the backend manifest (`keevo/modules/invoicing/module.json`).
const kInvoicingModuleId = 'invoicing';

/// « Facturer » (`pos.checkout.post-sale-actions`): opens the sale's
/// automatic invoice. Args: `saleId`. Label/order/permission are the
/// server's; this is only the key of the handler.
const kOpenSaleInvoiceAction = 'invoicing.open-sale-invoice';

/// Story 15.4c — the Invoicing module's UI, registered by the app shell
/// (`lib/core/modules/registered_modules.dart`): its screens (manifest
/// routes, 15.2 guard) and the « Facturer » handler. Story 15.5: list
/// `/invoicing`, creation `/invoicing/new`, seller settings
/// `/invoicing/settings`, invoice `/invoicing/:id`, draft edit
/// `/invoicing/:id/edit` — the static paths come before `/invoicing/:id`.
final invoicingModuleUi = KeevoModuleUi(
  id: kInvoicingModuleId,
  routes: [
    GoRoute(
      path: '/invoicing',
      builder: (_, _) => DeferredWidget(
        loader: invoice_list.loadLibrary,
        builder: (_) => invoice_list.InvoiceListPage(),
      ),
    ),
    GoRoute(
      path: '/invoicing/new',
      builder: (_, _) => DeferredWidget(
        loader: invoice_form.loadLibrary,
        builder: (_) => invoice_form.InvoiceFormPage(),
      ),
    ),
    GoRoute(
      path: '/invoicing/settings',
      builder: (_, _) => DeferredWidget(
        loader: invoicing_settings.loadLibrary,
        builder: (_) => invoicing_settings.InvoicingSettingsPage(),
      ),
    ),
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
    GoRoute(
      path: '/invoicing/:id/edit',
      builder: (_, state) {
        final id = state.pathParameters['id']!;
        return DeferredWidget(
          loader: invoice_form.loadLibrary,
          builder: (_) => invoice_form.InvoiceFormPage(invoiceId: id),
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

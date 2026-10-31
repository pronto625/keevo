import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/widget/deferred_widget.dart';
import 'package:keevo_invoicing/domain/model/invoice.dart';
import 'package:keevo_invoicing/invoicing_module.dart';
import 'package:keevo_invoicing/presentation/page/invoice_detail_page.dart';
import 'package:keevo_invoicing/presentation/provider/invoicing_providers.dart';

/// Story 15.4c — the module descriptor the app shell registers. Own file
/// (own isolate): a deferred library loaded inside another test's fake clock
/// would keep this one's `loadLibrary` pending.
void main() {
  group('invoicingModuleUi (15.4c descriptor)', () {
    test('module id, routes (15.5: static paths before /invoicing/:id), '
        '« Facturer » handler', () {
      expect(invoicingModuleUi.id, 'invoicing');
      expect(
        invoicingModuleUi.routes.map((r) => (r as GoRoute).path).toList(),
        [
          '/invoicing',
          '/invoicing/new',
          '/invoicing/settings',
          '/invoicing/:id',
          '/invoicing/:id/edit',
        ],
      );
      expect(invoicingModuleUi.actions.keys, [kOpenSaleInvoiceAction]);
      expect(kOpenSaleInvoiceAction, 'invoicing.open-sale-invoice');
    });

    testWidgets('/invoicing/inv-7 → InvoiceDetailPage through the loader', (
      tester,
    ) async {
      final router = GoRouter(
        initialLocation: '/invoicing/inv-7',
        routes: invoicingModuleUi.routes,
      );
      addTearDown(router.dispose);
      final pending = Completer<Invoice>();
      await tester.pumpWidget(
        ProviderScope(
          retry: noProviderRetry,
          overrides: [
            invoiceByIdProvider('inv-7').overrideWith((ref) => pending.future),
          ],
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      expect(find.byType(DeferredWidget), findsOneWidget);
      // loadLibrary completes outside the fake clock.
      for (
        var i = 0;
        i < 20 && find.byType(InvoiceDetailPage).evaluate().isEmpty;
        i++
      ) {
        await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 20)),
        );
        await tester.pump(const Duration(milliseconds: 20));
      }
      expect(
        find.byType(InvoiceDetailPage),
        findsOneWidget,
        reason: 'deferred library not loaded',
      );
      expect(
        tester
            .widget<InvoiceDetailPage>(find.byType(InvoiceDetailPage))
            .invoiceId,
        'inv-7',
      );
    });

    test('API port not overridden → error naming apiDioProvider', () {
      final c = ProviderContainer(retry: noProviderRetry);
      addTearDown(c.dispose);
      Object? error;
      try {
        c.read(invoiceDataSourceProvider);
      } catch (e) {
        error = e;
      }
      expect(error.toString(), contains('apiDioProvider'));
    });
  });
}

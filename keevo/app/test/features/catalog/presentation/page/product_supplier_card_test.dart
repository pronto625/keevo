import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/catalog/presentation/page/product_form_page.dart';
import 'package:keevo/features/contact/domain/model/supplier_model.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../../../helpers/fake_entitlements.dart';

/// Story 15.8 (routed from 15.2) — the product form's supplier card links to
/// the CRM supplier sheet only when CRM can be read.
void main() {
  final supplier = SupplierModel(
    id: 'sup-1',
    name: 'Grossiste Akwa',
    phone: '690000000',
    createdAt: DateTime(2026),
    updatedAt: DateTime(2026),
  );

  Future<void> pump(WidgetTester tester, Map<String, String> states) async {
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [entitlementsOverride(states)],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(
            path: '/',
            builder: (context, _) => Scaffold(
              body: ProductSupplierCard(
                  supplier: supplier, theme: Theme.of(context)),
            ),
          ),
          GoRoute(
            path: '/suppliers/:id',
            builder: (_, s) =>
                Scaffold(body: Text('FICHE ${s.pathParameters['id']}')),
          ),
        ]),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('CRM DISABLED: supplier shown, no chevron, tap goes nowhere',
      (tester) async {
    await pump(tester, {'crm': 'DISABLED'});
    expect(find.text('Grossiste Akwa'), findsOneWidget);
    expect(find.byIcon(Icons.arrow_forward_ios_rounded), findsNothing);

    await tester.tap(find.text('Grossiste Akwa'));
    await tester.pumpAndSettle();
    expect(find.textContaining('FICHE'), findsNothing);
    expect(find.text('Grossiste Akwa'), findsOneWidget);
  });

  testWidgets('CRM ENABLED: chevron shown, tap opens /suppliers/<id>',
      (tester) async {
    await pump(tester, {'crm': 'ENABLED'});
    expect(find.byIcon(Icons.arrow_forward_ios_rounded), findsOneWidget);

    await tester.tap(find.text('Grossiste Akwa'));
    await tester.pumpAndSettle();
    expect(find.text('FICHE sup-1'), findsOneWidget);
  });
}

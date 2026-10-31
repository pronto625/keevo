import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/widget/store_card.dart';
import 'package:keevo_core/sync/held_operations.dart';

/// Story 13.8b — a store created/changed while its module is off stays listed
/// with a French badge (no code); the address line is kept.
void main() {
  Future<void> pump(WidgetTester tester, Map<String, String> held,
      {String? address = 'Douala'}) async {
    final now = DateTime(2026, 10, 30);
    await tester.pumpWidget(ProviderScope(
      overrides: [heldEntityReasonsProvider.overrideWith((_) async => held)],
      child: MaterialApp(
        home: Scaffold(
          body: StoreCard(
            store: StoreModel(
                id: 'store-1',
                name: 'Boutique Akwa',
                address: address,
                createdAt: now,
                updatedAt: now),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('a held store shows « En attente — abonnement expiré »',
      (tester) async {
    await pump(tester, {'store-1': 'MODULE_READ_ONLY'});
    expect(find.text('En attente — abonnement expiré'), findsOneWidget);
    expect(find.text('Douala'), findsOneWidget);
  });

  testWidgets('a store without held operation has no held badge',
      (tester) async {
    await pump(tester, {});
    expect(find.textContaining('En attente —'), findsNothing);
    expect(find.text('Douala'), findsOneWidget);
  });

  testWidgets('no address, no hold: one-line tile (no subtitle)',
      (tester) async {
    await pump(tester, {}, address: null);
    expect(tester.widget<ListTile>(find.byType(ListTile)).subtitle, isNull);
  });

  testWidgets('no address but held: the badge alone as subtitle',
      (tester) async {
    await pump(tester, {'store-1': 'MODULE_INACTIVE'}, address: null);
    expect(tester.widget<ListTile>(find.byType(ListTile)).subtitle, isNotNull);
    expect(find.text('En attente — module non activé'), findsOneWidget);
  });
}
